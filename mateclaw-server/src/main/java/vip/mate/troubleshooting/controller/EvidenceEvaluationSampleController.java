package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evaluation.EvidenceEvaluationSample;
import vip.mate.troubleshooting.evaluation.EvidenceEvaluationSampleLedger;
import vip.mate.troubleshooting.evaluation.EvidenceEvaluationSampleService;
import vip.mate.troubleshooting.evaluation.EvidenceEvaluationSampleStore;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/** Admin-only HTTP surface for accumulating and curating T8 historical samples. */
@RestController
@RequestMapping("/api/v1/troubleshooting/evaluation-samples")
@RequiredArgsConstructor
public class EvidenceEvaluationSampleController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;
    private static final int DEFAULT_LIMIT = 100;

    private final EvidenceEvaluationSampleService service;

    /**
     * Re-runs Guance on the server and persists only its bounded structural projection.
     * The request cannot supply evidence, fixture flags, an outcome or an audit actor.
     */
    @PostMapping("/guance")
    @RequireWorkspaceRole("admin")
    public R<EvidenceEvaluationSampleStore.StoredSample> captureGuance(
            @Valid @RequestBody GuanceEvaluationSampleCaptureRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(service.capture(
                resolveWorkspace(workspaceId),
                request.diagnosisId(),
                request.scenarioKey(),
                request.searchTerm(),
                request.window(),
                currentActor()));
    }

    /** Finalizes structural intent keys; the linked closed Diagnosis owns the outcome. */
    @PutMapping("/{sampleId}/reference")
    @RequireWorkspaceRole("admin")
    public R<EvidenceEvaluationSample> finalizeReference(
            @PathVariable String sampleId,
            @Valid @RequestBody EvaluationSampleReferenceRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(service.finalizeReference(
                resolveWorkspace(workspaceId),
                sampleId,
                request.expectedVersion(),
                request.requiredStepIntents(),
                request.forbiddenStepIntents(),
                currentActor()));
    }

    /** Returns accumulation counts and rows without computing an acceptance verdict. */
    @GetMapping
    @RequireWorkspaceRole("admin")
    public R<EvidenceEvaluationSampleLedger> list(
            @RequestParam(required = false) String diagnosisId,
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
                    "evaluation sample changes require an authenticated operator");
        }
        return authentication.getName();
    }
}
