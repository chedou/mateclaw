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
import vip.mate.troubleshooting.model.DiagnosisDerivation;
import vip.mate.troubleshooting.service.DiagnosisDerivationService;
import vip.mate.troubleshooting.service.DiagnosisLifecycleService;
import vip.mate.troubleshooting.service.DiagnosisSummary;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingIntakeService;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * HTTP entry point for the troubleshooting domain.
 *
 * <p>This layer only converts protocol to domain calls. Routing, evidence
 * evaluation and lifecycle rules live in the domain services and the state
 * machine, so no diagnostic decision can be made here.</p>
 *
 * <p><b>Why not the trigger engine.</b> A trigger dispatches to an agent or a
 * workflow, and every workflow work-step invokes an LLM. Routing a known error
 * code must cost zero LLM calls, so intake is its own controller and the
 * trigger engine stays out of the hit path.</p>
 *
 * <p><b>Authentication.</b> Nothing bespoke: the platform JWT filter also
 * accepts personal access tokens (the {@code mc_} prefix distinguishes them),
 * so an alert source authenticates with a scoped PAT and lands as a normal
 * principal. Role checks then run through the standard workspace interceptor.</p>
 *
 * <p><b>Approval is not execution.</b> Approving a manual write only advances
 * action metadata to {@code APPROVED_NOT_EXECUTED}. An authorized human then
 * performs the change outside MateClaw and reports back through
 * {@code record-outcome}. {@code /execute} exists solely to answer 409.</p>
 */
@RestController
@RequestMapping("/api/v1/troubleshooting")
@RequiredArgsConstructor
public class TroubleshootingController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;
    private static final int DEFAULT_PAGE_SIZE = 50;

    private final TroubleshootingIntakeService intakeService;
    private final DiagnosisLifecycleService lifecycleService;
    private final DiagnosisDerivationService derivationService;
    private final TroubleshootingPersistenceService persistence;

    // ---------- intake and read ----------

    /**
     * Reports an incident and returns the resulting diagnosis.
     *
     * <p>Retries inside the five-minute deduplication bucket return the stored
     * diagnosis with {@code created=false}, so an alert source that fires twice
     * does not open two cases.</p>
     */
    @PostMapping("/incidents")
    @RequireWorkspaceRole("member")
    public R<StoredDiagnosis> report(
            @Valid @RequestBody IncidentReportRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        Instant receivedAt = Instant.now(Clock.systemUTC());
        return R.ok(intakeService.report(
                resolveWorkspace(workspaceId),
                request.toIncidentContext(receivedAt),
                request.evidenceOrEmpty(),
                request.isRehearsal()));
    }

    /** Duty queue. Reads indexed columns only, so listing never parses aggregates. */
    @GetMapping("/diagnoses")
    @RequireWorkspaceRole("viewer")
    public R<List<DiagnosisSummary>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String system,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(persistence.list(
                resolveWorkspace(workspaceId),
                status,
                system,
                limit == null ? DEFAULT_PAGE_SIZE : limit));
    }

    /** Reads one full diagnosis aggregate. */
    @GetMapping("/diagnoses/{diagnosisId}")
    @RequireWorkspaceRole("viewer")
    public R<StoredDiagnosis> get(
            @PathVariable String diagnosisId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(persistence.get(resolveWorkspace(workspaceId), diagnosisId));
    }

    /**
     * Explains how the diagnosis reached its conclusion.
     *
     * <p>Separate from the aggregate because it is a projection over the
     * diagnosis and its SOP, not stored state — and because the criteria behind
     * a conclusion belong to the knowledge base, which evolves independently of
     * any single case.</p>
     */
    @GetMapping("/diagnoses/{diagnosisId}/derivation")
    @RequireWorkspaceRole("viewer")
    public R<DiagnosisDerivation> derivation(
            @PathVariable String diagnosisId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(derivationService.explain(resolveWorkspace(workspaceId), diagnosisId));
    }

    // ---------- human-controlled lifecycle ----------

    /** Accepts the conclusion. Executes nothing. */
    @PostMapping("/diagnoses/{diagnosisId}/confirm")
    @RequireWorkspaceRole("member")
    public R<StoredDiagnosis> confirm(
            @PathVariable String diagnosisId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(lifecycleService.confirm(resolveWorkspace(workspaceId), diagnosisId, currentActor()));
    }

    /** Hands the case to a team with the full context snapshot attached. */
    @PostMapping("/diagnoses/{diagnosisId}/transfer")
    @RequireWorkspaceRole("member")
    public R<StoredDiagnosis> transfer(
            @PathVariable String diagnosisId,
            @Valid @RequestBody LifecycleRequests.Transfer request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(lifecycleService.transfer(
                resolveWorkspace(workspaceId), diagnosisId,
                request.targetTeam(), request.note(), currentActor()));
    }

    /**
     * Authorizes a manual write <em>without executing it</em>.
     *
     * <p>The action moves to {@code APPROVED_NOT_EXECUTED}; no tool runs. The
     * change is made by an authorized human outside MateClaw.</p>
     */
    @PostMapping("/diagnoses/{diagnosisId}/actions/{actionId}/approve")
    @RequireWorkspaceRole("member")
    public R<StoredDiagnosis> approve(
            @PathVariable String diagnosisId,
            @PathVariable String actionId,
            @Valid @RequestBody LifecycleRequests.Approve request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(lifecycleService.approveAction(
                resolveWorkspace(workspaceId), diagnosisId, actionId,
                request.reason(), currentActor()));
    }

    /** Records what happened when the approved write was performed elsewhere. */
    @PostMapping("/diagnoses/{diagnosisId}/actions/{actionId}/record-outcome")
    @RequireWorkspaceRole("member")
    public R<StoredDiagnosis> recordOutcome(
            @PathVariable String diagnosisId,
            @PathVariable String actionId,
            @Valid @RequestBody LifecycleRequests.RecordOutcome request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(lifecycleService.recordOutcome(
                resolveWorkspace(workspaceId), diagnosisId, actionId,
                request.outcome(), request.notes(), request.recoveryVerified(), currentActor()));
    }

    /** Closes the case and optionally sediments a reviewable knowledge candidate. */
    @PostMapping("/diagnoses/{diagnosisId}/close")
    @RequireWorkspaceRole("member")
    public R<StoredDiagnosis> close(
            @PathVariable String diagnosisId,
            @Valid @RequestBody LifecycleRequests.Close request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(lifecycleService.close(
                resolveWorkspace(workspaceId), diagnosisId,
                request.outcome(), request.summary(), request.recoveryVerified(),
                request.sopFeedback(), request.createKnowledgeCandidate(), currentActor()));
    }

    /**
     * Refuses to execute a recommended action, always.
     *
     * <p>The endpoint exists so the guarantee is visible and testable at the
     * HTTP boundary rather than implied: MateClaw has no production write
     * executor, approval only advances the state machine, and a write is
     * carried out by an authorized human outside the platform who then records
     * the outcome. Removing this endpoint would not enable execution — the
     * {@code Diagnosis} contract rejects an enabled write executor outright —
     * but its 409 documents the boundary to every caller.</p>
     */
    @PostMapping("/diagnoses/{diagnosisId}/actions/{actionId}/execute")
    @RequireWorkspaceRole("member")
    public R<Void> execute(
            @PathVariable String diagnosisId,
            @PathVariable String actionId) {
        throw new MateClawException(
                "err.troubleshooting.production_write_disabled",
                409,
                "production write executor is not connected; execute externally and record the outcome");
    }

    // ---------- helpers ----------

    private long resolveWorkspace(Long workspaceId) {
        return workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId;
    }

    /**
     * The operator recorded on every lifecycle transition.
     *
     * <p>Taken from the authenticated principal rather than the request body so
     * the audit trail on an approval cannot be attributed to someone else.</p>
     */
    private String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new MateClawException(
                    "err.troubleshooting.actor_required",
                    401,
                    "lifecycle transitions require an authenticated operator");
        }
        return auth.getName();
    }
}
