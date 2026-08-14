package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import vip.mate.audit.service.AuditEventService;
import vip.mate.common.net.SsrfProperties;
import vip.mate.system.service.SettingCrypto;
import vip.mate.tool.browser.UrlSafetyChecker;
import vip.mate.troubleshooting.agent.TroubleshootingAgentProperties;
import vip.mate.troubleshooting.model.TroubleshootingEvidenceSettingsEntity;
import vip.mate.troubleshooting.repository.TroubleshootingEvidenceSettingsMapper;

import java.time.LocalDateTime;

/**
 * Owns the per-workspace evidence source settings that used to live in
 * application.yml.
 *
 * <p>Three rules hold this together, and each exists because moving a
 * credential into workspace-editable storage removes a protection the yml
 * placement gave for free:
 *
 * <p><b>The key is encrypted and never read back.</b> It is stored as a
 * {@link SettingCrypto} envelope and leaves this class only as an
 * {@link EffectiveEvidenceSettings} handed to the adapter that is about to
 * make the call. Every browser-facing path gets {@link EvidenceSettingsView},
 * which has nowhere to put it.
 *
 * <p><b>The endpoint is SSRF-checked on write and again on use.</b> Once an
 * owner can type the Guance URL, the evidence path is one careless entry away
 * from being an internal HTTP probe. Write-time validation alone would not be
 * enough — a name that resolved publicly at save time can resolve to
 * 127.0.0.1 an hour later — so {@link #assertReachableEndpoint} is called
 * again immediately before the request. An on-prem Guance on a private address
 * is still reachable, but only by adding its host to the deployment's
 * {@code mateclaw.security.ssrf-allowlist}: the workspace chooses the URL, the
 * deployment still chooses which internal hosts exist.
 *
 * <p><b>Absence means "not configured", not "off".</b> A workspace with no row
 * inherits the deployment yml unchanged, so installs that predate this table
 * behave exactly as before.
 */
@Service
public class WorkspaceEvidenceSettingsService {

    private static final Logger log =
            LoggerFactory.getLogger(WorkspaceEvidenceSettingsService.class);

    private static final int MAX_URL_LENGTH = 512;
    private static final int MAX_REASON_LENGTH = 512;
    private static final String AUDIT_RESOURCE_TYPE = "TROUBLESHOOTING_EVIDENCE_SETTINGS";

    private final TroubleshootingEvidenceSettingsMapper mapper;
    private final EvidenceProperties deploymentDefaults;
    private final TroubleshootingAgentProperties agentDefaults;
    private final SettingCrypto crypto;
    private final SsrfProperties ssrfProperties;
    private final AuditEventService auditEvents;
    private final ObjectMapper objectMapper;

    public WorkspaceEvidenceSettingsService(
            TroubleshootingEvidenceSettingsMapper mapper,
            EvidenceProperties deploymentDefaults,
            TroubleshootingAgentProperties agentDefaults,
            SettingCrypto crypto,
            SsrfProperties ssrfProperties,
            AuditEventService auditEvents,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.deploymentDefaults = deploymentDefaults;
        this.agentDefaults = agentDefaults;
        this.crypto = crypto;
        this.ssrfProperties = ssrfProperties;
        this.auditEvents = auditEvents;
        this.objectMapper = objectMapper;
    }

    /**
     * The settings in force for this workspace right now.
     *
     * <p>Read per call rather than cached: an owner who turns a source on
     * expects the next diagnosis to see it, and a cache would reintroduce the
     * restart this table exists to remove.
     */
    public EffectiveEvidenceSettings effective(long workspaceId) {
        TroubleshootingEvidenceSettingsEntity row = mapper.findByWorkspace(workspaceId);
        if (row == null) {
            return deploymentSettings();
        }
        // Deferred: an enablement check should not pay for a decrypt, and
        // readiness inspection must not reach the credential before it has
        // authorized the asset scope.
        String stored = row.getGuanceApiKey();
        return new EffectiveEvidenceSettings(
                bool(row.getGuanceEnabled()),
                trimToNull(row.getGuanceBaseUrl()),
                () -> decrypt(workspaceId, stored),
                bool(row.getGuanceAllowInsecureHttp()),
                bool(row.getReplayEnabled()),
                bool(row.getAgentEnabled()),
                EffectiveEvidenceSettings.Origin.WORKSPACE);
    }

    /** The masked projection safe to hand to a browser. */
    public EvidenceSettingsView view(long workspaceId) {
        TroubleshootingEvidenceSettingsEntity row = mapper.findByWorkspace(workspaceId);
        if (row == null) {
            EffectiveEvidenceSettings fallback = deploymentSettings();
            return new EvidenceSettingsView(
                    workspaceId,
                    fallback.guanceEnabled(),
                    fallback.guanceBaseUrl(),
                    present(fallback.guanceApiKey()),
                    mask(fallback.guanceApiKey()),
                    fallback.guanceAllowInsecureHttp(),
                    fallback.replayEnabled(),
                    fallback.agentEnabled(),
                    0,
                    null,
                    null,
                    EffectiveEvidenceSettings.Origin.DEPLOYMENT);
        }
        String key = decrypt(workspaceId, row.getGuanceApiKey());
        return new EvidenceSettingsView(
                workspaceId,
                bool(row.getGuanceEnabled()),
                trimToNull(row.getGuanceBaseUrl()),
                present(key),
                mask(key),
                bool(row.getGuanceAllowInsecureHttp()),
                bool(row.getReplayEnabled()),
                bool(row.getAgentEnabled()),
                row.getVersion() == null ? 0 : row.getVersion(),
                row.getChangedBy(),
                row.getChangeReason(),
                EffectiveEvidenceSettings.Origin.WORKSPACE);
    }

    /**
     * Apply an owner-submitted change.
     *
     * @throws SecurityException        if the endpoint fails SSRF validation
     * @throws IllegalArgumentException if the submission is internally
     *                                  inconsistent, e.g. Guance switched on
     *                                  with no endpoint
     * @throws IllegalStateException    if another writer won the version race
     */
    public EvidenceSettingsView save(long workspaceId, EvidenceSettingsUpdate update, String actor) {
        String baseUrl = normalizeUrl(update.guanceBaseUrl());
        TroubleshootingEvidenceSettingsEntity existing = mapper.findByWorkspace(workspaceId);
        String storedKey = existing == null ? null : existing.getGuanceApiKey();
        String nextKey = resolveKey(update.guanceApiKey(), storedKey);

        if (update.guanceEnabled()) {
            if (baseUrl == null) {
                throw new IllegalArgumentException("Guance is enabled but no base URL was given");
            }
            if (!present(nextKey)) {
                throw new IllegalArgumentException("Guance is enabled but no API key is stored");
            }
        }
        if (baseUrl != null) {
            assertScheme(baseUrl, update.guanceAllowInsecureHttp());
            assertReachableEndpoint(baseUrl);
        }

        int expected = update.expectedVersion();
        int next = expected + 1;
        TroubleshootingEvidenceSettingsEntity entity = new TroubleshootingEvidenceSettingsEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setGuanceEnabled(update.guanceEnabled());
        entity.setGuanceBaseUrl(baseUrl);
        entity.setGuanceApiKey(nextKey);
        entity.setGuanceAllowInsecureHttp(update.guanceAllowInsecureHttp());
        entity.setReplayEnabled(update.replayEnabled());
        entity.setAgentEnabled(update.agentEnabled());
        entity.setVersion(next);
        entity.setChangedBy(actor);
        entity.setChangeReason(truncate(update.changeReason(), MAX_REASON_LENGTH));

        if (existing == null) {
            if (expected != 0) {
                throw new IllegalStateException(
                        "expected version " + expected + " but this workspace has no settings row");
            }
            entity.setCreateTime(LocalDateTime.now());
            entity.setUpdateTime(entity.getCreateTime());
            // A concurrent first write surfaces as a duplicate-key failure from
            // the primary key rather than two rows; the caller re-reads.
            mapper.insert(entity);
        } else {
            int changed = mapper.updateIfVersionMatches(entity, expected, next);
            if (changed == 0) {
                throw new IllegalStateException(
                        "evidence settings changed since you loaded them; reload and re-apply");
            }
        }

        audit(workspaceId, existing, entity);
        return view(workspaceId);
    }

    /**
     * Re-validate an endpoint immediately before it is used.
     *
     * <p>Separate from the write path deliberately. Save-time validation
     * cannot bind a DNS answer, so a hostname that passed then may point
     * somewhere private now; and a row written by a database administrator
     * never passed through {@link #save} at all.
     */
    public void assertReachableEndpoint(String baseUrl) {
        UrlSafetyChecker.check(baseUrl, ssrfProperties.getSsrfAllowlist(), false);
    }

    private EffectiveEvidenceSettings deploymentSettings() {
        EvidenceProperties.Guance guance = deploymentDefaults.getGuance();
        return new EffectiveEvidenceSettings(
                guance.isEnabled(),
                trimToNull(guance.getBaseUrl()),
                () -> trimToNull(guance.getApiKey()),
                guance.isAllowInsecureHttp(),
                deploymentDefaults.getRecordedReplay().isEnabled(),
                // Agent enablement lives in a different properties bean than
                // the evidence sources, but from a workspace's point of view
                // it is the same switchboard, so both fall back together.
                agentDefaults.isEnabled(),
                EffectiveEvidenceSettings.Origin.DEPLOYMENT);
    }

    private String resolveKey(String submitted, String stored) {
        if (submitted == null) {
            return stored;
        }
        if (submitted.isBlank()) {
            return null;
        }
        return crypto.encrypt(submitted.trim());
    }

    /**
     * Decrypt a stored credential, or report none.
     *
     * <p>{@link SettingCrypto#decrypt} already fails soft: a wrong key or a
     * corrupt envelope yields an empty string rather than an exception, so it
     * never hands back ciphertext as though it were the secret. Normalizing
     * that to {@code null} here keeps one meaning for "no usable credential",
     * so callers cannot accidentally treat an empty key as a configured one.
     */
    private String decrypt(long workspaceId, String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        String plaintext = crypto.isEncrypted(stored) ? crypto.decrypt(stored) : stored;
        if (plaintext == null || plaintext.isBlank()) {
            log.error("Workspace {} has a Guance credential that could not be read; treating the "
                    + "source as unconfigured. Was MATECLAW_SETTING_KEY rotated or lost?", workspaceId);
            return null;
        }
        return plaintext;
    }

    private void assertScheme(String baseUrl, boolean allowInsecureHttp) {
        String lower = baseUrl.toLowerCase();
        if (lower.startsWith("https://")) {
            return;
        }
        if (lower.startsWith("http://") && allowInsecureHttp) {
            return;
        }
        throw new IllegalArgumentException(
                "Guance base URL must use HTTPS unless insecure HTTP is explicitly allowed");
    }

    private void audit(long workspaceId,
                       TroubleshootingEvidenceSettingsEntity before,
                       TroubleshootingEvidenceSettingsEntity after) {
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("guanceEnabled", bool(after.getGuanceEnabled()));
        detail.put("guanceBaseUrl", after.getGuanceBaseUrl());
        detail.put("guanceAllowInsecureHttp", bool(after.getGuanceAllowInsecureHttp()));
        detail.put("replayEnabled", bool(after.getReplayEnabled()));
        detail.put("agentEnabled", bool(after.getAgentEnabled()));
        detail.put("version", after.getVersion());
        // Whether the credential moved, never what it is or was.
        String beforeKey = before == null ? null : before.getGuanceApiKey();
        detail.put("apiKeyChanged", !java.util.Objects.equals(beforeKey, after.getGuanceApiKey()));
        detail.put("apiKeyPresent", present(after.getGuanceApiKey()));
        if (after.getChangeReason() != null) {
            detail.put("changeReason", after.getChangeReason());
        }
        auditEvents.record(
                before == null ? "CREATE" : "UPDATE",
                AUDIT_RESOURCE_TYPE,
                String.valueOf(workspaceId),
                "troubleshooting evidence settings",
                detail.toString(),
                workspaceId);
    }

    private String normalizeUrl(String raw) {
        String trimmed = trimToNull(raw);
        if (trimmed == null) {
            return null;
        }
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("Guance base URL is too long");
        }
        // A trailing slash would double up against the query path and is the
        // single most common paste error; normalize rather than reject.
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String mask(String key) {
        if (!present(key)) {
            return null;
        }
        String trimmed = key.trim();
        return trimmed.length() <= 4 ? "****" : "****" + trimmed.substring(trimmed.length() - 4);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean bool(Boolean value) {
        return value != null && value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
