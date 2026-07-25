package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingIntakeService;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.time.Clock;
import java.time.Instant;

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
 */
@RestController
@RequestMapping("/api/v1/troubleshooting")
@RequiredArgsConstructor
public class TroubleshootingController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final TroubleshootingIntakeService intakeService;
    private final TroubleshootingPersistenceService persistence;

    /**
     * Reports an incident and returns the resulting diagnosis.
     *
     * <p>Retries inside the five-minute deduplication bucket return the stored
     * diagnosis with {@code created=false}, so an alert source that fires twice
     * does not open two cases.</p>
     *
     * <p>Requires {@code operate:troubleshooting} (member and above): reporting
     * an incident creates domain state.</p>
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

    /**
     * Reads one diagnosis. Requires {@code view:troubleshooting} (viewer and above).
     */
    @GetMapping("/diagnoses/{diagnosisId}")
    @RequireWorkspaceRole("viewer")
    public R<StoredDiagnosis> get(
            @PathVariable String diagnosisId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(persistence.get(resolveWorkspace(workspaceId), diagnosisId));
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

    private long resolveWorkspace(Long workspaceId) {
        return workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId;
    }
}
