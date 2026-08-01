package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.deployment.DeploymentTopologyScenarioDiagnosisService;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.time.Instant;

/** Explicitly creates the Diagnosis owner before the topology Tool may run. */
@RestController
@RequestMapping("/api/v1/troubleshooting/scenarios/deployment-topology/diagnoses")
@RequiredArgsConstructor
public class DeploymentTopologyScenarioController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final DeploymentTopologyScenarioDiagnosisService service;

    @PostMapping
    @RequireWorkspaceRole("admin")
    public R<StoredDiagnosis> create(
            @Valid @RequestBody DeploymentTopologyScenarioRequest request,
            @RequestAttribute(TroubleshootingRequestTimingFilter.REPORTED_AT_ATTRIBUTE)
                    Instant reportedAt,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(service.create(
                workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId,
                request.toIncidentContext(reportedAt),
                request.isRehearsal(),
                currentActor(),
                reportedAt));
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
                    "scenario diagnosis creation requires an authenticated operator");
        }
        return authentication.getName();
    }
}
