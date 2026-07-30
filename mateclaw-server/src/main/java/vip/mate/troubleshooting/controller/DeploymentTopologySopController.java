package vip.mate.troubleshooting.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.deployment.DeploymentTopologyAssetSummary;
import vip.mate.troubleshooting.deployment.DeploymentTopologyImportResult;
import vip.mate.troubleshooting.deployment.DeploymentTopologyLibraryService;
import vip.mate.troubleshooting.deployment.DeploymentTopologySopResult;
import vip.mate.troubleshooting.deployment.DeploymentTopologySopService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

/** Admin-managed shared topology library and read-only live analysis entry. */
@RestController
@RequestMapping("/api/v1/troubleshooting/sops/deployment-topology")
@RequiredArgsConstructor
public class DeploymentTopologySopController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final DeploymentTopologySopService service;
    private final DeploymentTopologyLibraryService library;

    @GetMapping("/topologies")
    @RequireWorkspaceRole("admin")
    public R<List<DeploymentTopologyAssetSummary>> listTopologies(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(library.list(resolveWorkspace(workspaceId), 100));
    }

    @PostMapping("/topologies")
    @RequireWorkspaceRole("admin")
    public R<DeploymentTopologyImportResult> importTopology(
            @Valid @RequestBody DeploymentTopologyImportRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(library.importTopology(
                resolveWorkspace(workspaceId),
                request.name(),
                request.snapshot(),
                currentActor()));
    }

    @GetMapping("/example")
    @RequireWorkspaceRole("admin")
    public R<ObjectNode> example() {
        return R.ok(library.example());
    }

    @PostMapping("/topologies/{topologyId}/analyze")
    @RequireWorkspaceRole("admin")
    public R<DeploymentTopologySopResult> analyzeImported(
            @PathVariable String topologyId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(library.analyze(resolveWorkspace(workspaceId), topologyId));
    }

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
                resolveWorkspace(workspaceId),
                request.snapshot()));
    }

    private long resolveWorkspace(Long workspaceId) {
        return workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId;
    }

    private String currentActor() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new MateClawException(
                    "err.troubleshooting.actor_required",
                    401,
                    "deployment topology import requires an authenticated operator");
        }
        return authentication.getName();
    }
}
