package vip.mate.troubleshooting.agent;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.evidence.EvidenceSpinePlan;
import vip.mate.troubleshooting.model.IncidentContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Resolves an approved scenario key into a complete, server-owned Evidence Spine plan. */
@Component
public final class ApprovedEvidenceSpineCatalog {

    private static final Pattern SAFE_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SAFE_PLATFORM =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final TroubleshootingAgentProperties properties;

    public ApprovedEvidenceSpineCatalog(TroubleshootingAgentProperties properties) {
        this.properties = properties;
    }

    public List<String> visibleScenarioKeys(long workspaceId, IncidentContext incident) {
        if (workspaceId <= 0 || incident == null) {
            return List.of();
        }
        return configuredPlans().entrySet().stream()
                .map(entry -> approvedIfVisible(
                        workspaceId, incident, entry.getKey(), entry.getValue()))
                .filter(Objects::nonNull)
                .map(ApprovedSpinePlan::scenarioKey)
                .sorted()
                .toList();
    }

    public ApprovedSpinePlan resolve(
            long workspaceId,
            IncidentContext incident,
            String scenarioKey) {
        if (workspaceId <= 0 || incident == null) {
            throw new IllegalArgumentException(
                    "workspace and incident are required to resolve an approved plan");
        }
        String key = safeKey(scenarioKey);
        TroubleshootingAgentProperties.ScenarioEvidencePlan configured =
                configuredPlans().get(key);
        ApprovedSpinePlan approved = approvedIfVisible(
                workspaceId, incident, key, configured);
        if (approved == null) {
            throw new IllegalArgumentException(
                    "scenario key is not an approved workspace-visible plan");
        }
        return approved;
    }

    private Map<String, TroubleshootingAgentProperties.ScenarioEvidencePlan> configuredPlans() {
        Map<String, TroubleshootingAgentProperties.ScenarioEvidencePlan> configured =
                properties.getApprovedScenarioPlans();
        return configured == null ? Map.of() : configured;
    }

    private ApprovedSpinePlan approvedIfVisible(
            long workspaceId,
            IncidentContext incident,
            String scenarioKey,
            TroubleshootingAgentProperties.ScenarioEvidencePlan plan) {
        if (plan == null
                || workspaceId <= 0
                || incident == null
                || !plan.isEnabled()
                || scenarioKey == null
                || !SAFE_KEY.matcher(scenarioKey).matches()
                || plan.getSystem() == null
                || !normalize(plan.getSystem()).equals(normalize(incident.system()))
                || plan.getWorkspaceIds() == null
                || !plan.getWorkspaceIds().contains(workspaceId)) {
            return null;
        }
        try {
            Set<String> platforms = safePlatforms(plan.getPermittedPlatforms());
            for (String extra : extraPlatforms()) {
                platforms = withPlatform(platforms, extra);
            }
            EvidenceSpinePlan evidencePlan = new EvidenceSpinePlan(
                    TroubleshootingEvidenceSessionRegistry.ONLINE_SEARCH_REQUEST_ID,
                    TroubleshootingEvidenceSessionRegistry.ONLINE_TRACE_REQUEST_ID,
                    TroubleshootingEvidenceSessionRegistry.ONLINE_CONTRAST_REQUEST_ID,
                    plan.getSearchTerm(),
                    plan.getWindow());
            return new ApprovedSpinePlan(scenarioKey, evidencePlan, platforms);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private List<String> extraPlatforms() {
        List<String> configured = properties.getExtraPermittedPlatforms();
        return configured == null ? List.of() : configured;
    }

    private Set<String> withPlatform(Set<String> platforms, String platform) {
        if (platform == null || !SAFE_PLATFORM.matcher(platform.trim()).matches()) {
            return platforms;
        }
        Set<String> expanded = new LinkedHashSet<>(platforms);
        expanded.add(normalize(platform));
        return Set.copyOf(expanded);
    }

    private String safeKey(String value) {
        if (value == null || !SAFE_KEY.matcher(value.trim()).matches()) {
            throw new IllegalArgumentException("scenario key must be a safe registered key");
        }
        return value.trim();
    }

    private Set<String> safePlatforms(List<String> configured) {
        if (configured == null || configured.isEmpty()) {
            throw new IllegalArgumentException(
                    "approved evidence plan requires an explicit platform allowlist");
        }
        Set<String> platforms = new LinkedHashSet<>();
        for (String platform : configured) {
            if (platform == null || !SAFE_PLATFORM.matcher(platform.trim()).matches()) {
                throw new IllegalArgumentException(
                        "approved evidence plan contains an invalid platform");
            }
            platforms.add(normalize(platform));
        }
        if (platforms.isEmpty()) {
            throw new IllegalArgumentException(
                    "approved evidence plan requires an explicit platform allowlist");
        }
        return Set.copyOf(platforms);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record ApprovedSpinePlan(
            String scenarioKey,
            EvidenceSpinePlan evidencePlan,
            Set<String> permittedPlatforms) {

        /** Stable, non-reversible identity for the exact server-owned plan. */
        public String fingerprint() {
            String canonical = String.join(
                    "\u001f",
                    "approved-evidence-spine.v1",
                    scenarioKey,
                    evidencePlan.searchRequestId(),
                    evidencePlan.traceRequestId(),
                    evidencePlan.contrastRequestId(),
                    evidencePlan.searchTerm(),
                    evidencePlan.window(),
                    permittedPlatforms.stream().sorted().collect(
                            java.util.stream.Collectors.joining(",")));
            try {
                return HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(
                                canonical.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is unavailable", impossible);
            }
        }
    }
}
