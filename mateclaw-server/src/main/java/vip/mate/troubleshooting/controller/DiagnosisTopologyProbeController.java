package vip.mate.troubleshooting.controller;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.deployment.TopologyProbeEvidenceRun;
import vip.mate.troubleshooting.deployment.TopologyProbeEvidenceRunService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

/** Diagnosis-owned entry and history for the deployment-topology scenario. */
@RestController
@RequestMapping("/api/v1/troubleshooting/diagnoses/{diagnosisId}/topology-probe-runs")
@RequiredArgsConstructor
public class DiagnosisTopologyProbeController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;
    private static final int DEFAULT_LIMIT = 50;

    private final TopologyProbeEvidenceRunService service;

    @PostMapping
    @RequireWorkspaceRole("admin")
    public R<TopologyProbeEvidenceRun> run(
            @PathVariable String diagnosisId,
            @Valid @RequestBody TopologyProbeRunRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(service.run(
                resolveWorkspace(workspaceId),
                diagnosisId,
                request.topologyId(),
                currentActor()));
    }

    @GetMapping
    @RequireWorkspaceRole("viewer")
    public R<List<TopologyProbeEvidenceRun>> list(
            @PathVariable String diagnosisId,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(service.list(
                resolveWorkspace(workspaceId),
                diagnosisId,
                limit == null ? DEFAULT_LIMIT : limit));
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
                    "topology evidence collection requires an authenticated operator");
        }
        return authentication.getName();
    }
}
