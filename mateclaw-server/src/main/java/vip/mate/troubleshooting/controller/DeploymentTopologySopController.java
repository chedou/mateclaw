package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.troubleshooting.deployment.DeploymentTopologySopResult;
import vip.mate.troubleshooting.deployment.DeploymentTopologySopService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/** Admin-triggered, read-only live entry for the deployment-topology SOP. */
@RestController
@RequestMapping("/api/v1/troubleshooting/sops/deployment-topology")
@RequiredArgsConstructor
public class DeploymentTopologySopController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final DeploymentTopologySopService service;

    /**
     * Parses the snapshot, runs each configured probe through the Guance-only
     * evidence route and returns a bounded topology analysis. Nothing is persisted.
     */
    @PostMapping("/analyze")
    @RequireWorkspaceRole("admin")
    public R<DeploymentTopologySopResult> analyze(
            @Valid @RequestBody DeploymentTopologySopRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(service.analyze(
                workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId,
                request.snapshot()));
    }
}
