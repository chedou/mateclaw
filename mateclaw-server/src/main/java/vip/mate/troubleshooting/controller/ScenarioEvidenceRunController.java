package vip.mate.troubleshooting.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.service.ScenarioEvidenceRunService;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/**
 * The step that turns a waiting scenario investigation into an answered one.
 *
 * <p>{@code POST /scenarios/{key}/diagnoses} opens the investigation and stops
 * there by design — naming a scenario selects an evidence plan, it does not
 * assert a cause. This runs that plan. Until it existed, only deployment
 * topology had a way to supply the evidence its own Diagnosis was waiting for.</p>
 *
 * <p>It takes no body. Everything it needs — which Playbook version, which
 * evidence requests, which incident context — is already frozen on the
 * Diagnosis. Accepting caller-supplied evidence here would let the requester
 * choose what the investigation concludes.</p>
 */
@RestController
@RequestMapping("/api/v1/troubleshooting/diagnoses/{diagnosisId}/evidence-runs")
@RequiredArgsConstructor
public class ScenarioEvidenceRunController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final ScenarioEvidenceRunService service;

    @PostMapping
    @RequireWorkspaceRole("member")
    public R<StoredDiagnosis> run(
            @PathVariable String diagnosisId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(service.run(
                workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId,
                diagnosisId,
                currentActor()));
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
                    "running a scenario evidence plan requires an authenticated operator");
        }
        return authentication.getName();
    }
}
