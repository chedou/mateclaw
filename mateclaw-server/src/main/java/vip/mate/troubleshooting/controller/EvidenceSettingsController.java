package vip.mate.troubleshooting.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.EvidenceSettingsUpdate;
import vip.mate.troubleshooting.evidence.EvidenceSettingsView;
import vip.mate.troubleshooting.evidence.WorkspaceEvidenceSettingsService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/**
 * Runtime switchboard for a workspace's evidence sources.
 *
 * <p>These settings used to be process-wide application.yml entries that
 * needed a restart and applied to every tenant at once. They are now a
 * workspace row, so a pilot tenant can be switched on without touching anyone
 * else and without a deploy.
 *
 * <p>The credential is write-only: {@link EvidenceSettingsView} has no field
 * that can carry it, and omitting {@code guanceApiKey} from an update means
 * "keep the stored one" so the URL can be edited without retyping a key nobody
 * can read back.
 */
@RestController
@RequestMapping("/api/v1/troubleshooting/evidence-settings")
@RequiredArgsConstructor
public class EvidenceSettingsController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final WorkspaceEvidenceSettingsService settingsService;

    @GetMapping
    @RequireWorkspaceRole("admin")
    public R<EvidenceSettingsView> current(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(settingsService.view(resolveWorkspace(workspaceId)));
    }

    @PutMapping
    @RequireWorkspaceRole("admin")
    public R<EvidenceSettingsView> save(
            @RequestBody SettingsRequest body,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        if (body == null) {
            throw new IllegalArgumentException("request body is required");
        }
        EvidenceSettingsUpdate update = new EvidenceSettingsUpdate(
                Boolean.TRUE.equals(body.guanceEnabled()),
                body.guanceBaseUrl(),
                body.guanceApiKey(),
                Boolean.TRUE.equals(body.guanceAllowInsecureHttp()),
                Boolean.TRUE.equals(body.replayEnabled()),
                Boolean.TRUE.equals(body.agentEnabled()),
                body.expectedVersion() == null ? 0 : body.expectedVersion(),
                body.changeReason());
        long workspace = resolveWorkspace(workspaceId);
        String actor = currentActor();
        try {
            return R.ok(settingsService.save(workspace, update, actor));
        } catch (SecurityException e) {
            // An endpoint the deployment refuses to call. The operator picked it,
            // so it is a rejected input, not a server fault.
            throw new MateClawException(
                    "err.troubleshooting.evidence_settings_endpoint_blocked", 400, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new MateClawException(
                    "err.troubleshooting.evidence_settings_invalid", 400, e.getMessage());
        } catch (IllegalStateException e) {
            // Lost the optimistic-locking race, or a version that never existed.
            throw new MateClawException(
                    "err.troubleshooting.evidence_settings_conflict", 409, e.getMessage());
        }
    }

    private long resolveWorkspace(Long workspaceId) {
        return workspaceId == null || workspaceId <= 0 ? DEFAULT_WORKSPACE_ID : workspaceId;
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return "unknown";
        }
        return authentication.getName();
    }

    /**
     * @param guanceApiKey  omit to keep the stored credential, send {@code ""}
     *                      to clear it
     */
    public record SettingsRequest(
            Boolean guanceEnabled,
            String guanceBaseUrl,
            String guanceApiKey,
            Boolean guanceAllowInsecureHttp,
            Boolean replayEnabled,
            Boolean agentEnabled,
            Integer expectedVersion,
            String changeReason) {
    }
}
