package vip.mate.troubleshooting.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.troubleshooting.agent.OpenDiscoveryReadiness;
import vip.mate.troubleshooting.agent.OpenDiscoveryReadinessService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/**
 * Secret-free readiness for the OPEN_DISCOVERY / miss-path night-time fallback.
 * Does not call a model or probe Guance.
 */
@RestController
@RequestMapping("/api/v1/troubleshooting/open-discovery")
@RequiredArgsConstructor
public class OpenDiscoveryReadinessController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final OpenDiscoveryReadinessService readinessService;

    @GetMapping("/readiness")
    @RequireWorkspaceRole("viewer")
    public R<OpenDiscoveryReadiness> readiness(
            @RequestParam(required = false) String system,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(readinessService.inspect(resolveWorkspace(workspaceId), system));
    }

    private long resolveWorkspace(Long workspaceId) {
        return workspaceId == null || workspaceId <= 0 ? DEFAULT_WORKSPACE_ID : workspaceId;
    }
}
