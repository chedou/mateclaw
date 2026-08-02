package vip.mate.troubleshooting.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingBusinessTextPolicy;
import vip.mate.troubleshooting.TroubleshootingSafetyPolicy;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.ScenarioDiagnosisDraft;
import vip.mate.troubleshooting.model.ScenarioSelector;
import vip.mate.troubleshooting.statemachine.DiagnosisStateMachine;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Creates the Diagnosis owner for an explicitly selected scenario, for any
 * registered scenario key.
 *
 * <p><b>Why this was extracted.</b> This capability already existed, but only
 * inside the deployment-topology service, so it was reachable for exactly one
 * scenario. The consequence showed up at the far end of the product: a report
 * with no error code could not produce a diagnosis at all. That looked like a
 * contract limitation — {@code Diagnosis} offers no shape for "no route matched
 * and no model was consulted" — but the real situation is narrower and better.
 * {@code DETERMINISTIC + SCENARIO_PLAYBOOK + EXPLICIT} is a perfectly legal
 * shape that needs neither an error code nor a model. The machinery to build it
 * was generic already; only its entry point was not.</p>
 *
 * <p><b>What it deliberately does not do.</b> It does not conclude anything.
 * The diagnosis it creates is {@code INSUFFICIENT_EVIDENCE} and
 * {@code NEEDS_INVESTIGATION}: naming a scenario selects <em>which read-only
 * evidence plan applies</em>, it does not assert a cause. Anything stronger
 * would let a caller obtain a conclusion by naming a scenario, which is exactly
 * the "model picks the answer" failure the route/authority split exists to
 * prevent.</p>
 *
 * <p>Authority resolution is unchanged from the topology path: the active
 * approved version is row-locked and re-checked inside the same transaction as
 * the insert, so the Playbook cannot be swapped between the check and the write.</p>
 */
@Service
public class ScenarioDiagnosisService {

    private final TroubleshootingPlaybookVersionService versions;
    private final TroubleshootingPersistenceService persistence;
    private final DiagnosisStateMachine stateMachine;
    private final Clock clock;
    private final Supplier<String> correlationIds;

    @Autowired
    public ScenarioDiagnosisService(
            TroubleshootingPlaybookVersionService versions,
            TroubleshootingPersistenceService persistence,
            DiagnosisStateMachine stateMachine) {
        this(versions, persistence, stateMachine, Clock.systemUTC(),
                () -> UUID.randomUUID().toString().replace("-", ""));
    }

    /** Deterministic seam: callers that need a fixed clock or id supply their own. */
    public ScenarioDiagnosisService(
            TroubleshootingPlaybookVersionService versions,
            TroubleshootingPersistenceService persistence,
            DiagnosisStateMachine stateMachine,
            Clock clock,
            Supplier<String> correlationIds) {
        this.versions = Objects.requireNonNull(versions, "versions");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.correlationIds = Objects.requireNonNull(correlationIds, "correlationIds");
    }

    /**
     * Extra check a specific scenario may impose on its own authority, beyond
     * "it is an operational approved version". The topology scenario uses it to
     * demand that the Playbook actually requires its synthetic probe.
     */
    @FunctionalInterface
    public interface AuthorityGuard {
        /** @return null when acceptable, otherwise the reason to fail closed. */
        String rejectionReason(ApprovedPlaybookVersion authority, String selectorKey);
    }

    /** Locks the active authority through the Diagnosis insert, then returns the idempotent owner. */
    @Transactional
    public StoredDiagnosis create(
            long workspaceId,
            IncidentContext incident,
            String scenarioKey,
            boolean rehearsal,
            String actor,
            Instant reportedAt,
            List<String> warnings,
            AuthorityGuard guard) {
        if (workspaceId <= 0 || incident == null || reportedAt == null) {
            throw invalid("workspaceId, incident and reportedAt are required");
        }
        String safeActor = required(actor, "actor");
        String safeScenarioKey = required(scenarioKey, "scenarioKey");
        IncidentContext safeIncident = TroubleshootingSecretRedactor.redact(incident);
        try {
            TroubleshootingBusinessTextPolicy.requireNoDeveloperEvidence(safeIncident);
        } catch (IllegalArgumentException unsafe) {
            throw invalid(unsafe.getMessage());
        }

        String selector = new ScenarioSelector(safeIncident.system(), safeScenarioKey)
                .routingKey();
        ApprovedPlaybookVersion authority = lockAuthority(workspaceId, selector);
        if (guard != null) {
            String rejection = guard.rejectionReason(authority, selector);
            if (rejection != null) {
                throw conflict(rejection);
            }
        }

        Instant observedReadyAt = clock.instant();
        Instant readyAt = observedReadyAt.isBefore(reportedAt) ? reportedAt : observedReadyAt;
        String correlationId = required(correlationIds.get(), "correlationId");
        ScenarioDiagnosisDraft draft = new ScenarioDiagnosisDraft(
                "diag-" + correlationId,
                "case-" + correlationId,
                "run-" + correlationId,
                safeIncident,
                safeScenarioKey,
                authority.playbook(),
                new PlaybookVersionRef(authority.playbookId(), authority.playbookVersion()),
                safeActor,
                NorthStarTimings.concluded(reportedAt, readyAt, readyAt),
                rehearsal,
                TroubleshootingSafetyPolicy.EVIDENCE_IS_FIXTURE,
                warnings == null ? List.of() : List.copyOf(warnings));
        return persistence.createOrGetForScenario(
                workspaceId,
                stateMachine.initializeScenarioAwaitingEvidence(draft),
                safeScenarioKey,
                reportedAt);
    }

    private ApprovedPlaybookVersion lockAuthority(long workspaceId, String selector) {
        PlaybookVersionRef activeRef = versions.activeRef(workspaceId, selector)
                .orElseThrow(() -> conflict(
                        "no approved scenario Playbook is active for " + selector));
        ApprovedPlaybookVersion authority = versions.lockActiveApprovedByPlaybookId(
                        workspaceId, activeRef.playbookId())
                .orElseThrow(() -> conflict(
                        "the scenario Playbook changed concurrently; retry"));
        if (!activeRef.equals(new PlaybookVersionRef(
                authority.playbookId(), authority.playbookVersion()))) {
            throw conflict("the scenario Playbook changed concurrently; retry");
        }
        if (!"APPROVED".equals(authority.status())
                || !authority.playbook().operational()
                || !authority.playbookId().equals(authority.playbook().sopId())) {
            throw conflict("the scenario Playbook is not an operational authority");
        }
        return authority;
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " must not be blank");
        }
        return value.trim();
    }

    private MateClawException invalid(String message) {
        return new MateClawException("err.troubleshooting.invalid_request", 400, message);
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.scenario_conflict", 409, message);
    }
}
