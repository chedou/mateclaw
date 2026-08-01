package vip.mate.troubleshooting.deployment;

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
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.service.TroubleshootingPlaybookVersionService;
import vip.mate.troubleshooting.statemachine.DiagnosisStateMachine;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Creates the Diagnosis owner for an explicitly selected deployment-topology scenario. */
@Service
public class DeploymentTopologyScenarioDiagnosisService {

    private final TroubleshootingPlaybookVersionService versions;
    private final TroubleshootingPersistenceService persistence;
    private final DeploymentTopologyScenarioPolicy policy;
    private final DiagnosisStateMachine stateMachine;
    private final Clock clock;
    private final Supplier<String> correlationIds;

    @Autowired
    public DeploymentTopologyScenarioDiagnosisService(
            TroubleshootingPlaybookVersionService versions,
            TroubleshootingPersistenceService persistence,
            DeploymentTopologyScenarioPolicy policy,
            DiagnosisStateMachine stateMachine) {
        this(
                versions,
                persistence,
                policy,
                stateMachine,
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString().replace("-", ""));
    }

    DeploymentTopologyScenarioDiagnosisService(
            TroubleshootingPlaybookVersionService versions,
            TroubleshootingPersistenceService persistence,
            DeploymentTopologyScenarioPolicy policy,
            DiagnosisStateMachine stateMachine,
            Clock clock,
            Supplier<String> correlationIds) {
        this.versions = Objects.requireNonNull(versions, "versions");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.stateMachine = Objects.requireNonNull(stateMachine, "stateMachine");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.correlationIds = Objects.requireNonNull(correlationIds, "correlationIds");
    }

    /** Locks the active authority through the Diagnosis insert, then returns the idempotent owner. */
    @Transactional
    public StoredDiagnosis create(
            long workspaceId,
            IncidentContext incident,
            boolean rehearsal,
            String actor,
            Instant reportedAt) {
        if (workspaceId <= 0 || incident == null || reportedAt == null) {
            throw invalid("workspaceId, incident and reportedAt are required");
        }
        String safeActor = required(actor, "actor");
        IncidentContext safeIncident = TroubleshootingSecretRedactor.redact(incident);
        try {
            TroubleshootingBusinessTextPolicy.requireNoDeveloperEvidence(safeIncident);
        } catch (IllegalArgumentException unsafe) {
            throw invalid(unsafe.getMessage());
        }

        String selector = policy.selectorFor(safeIncident.system());
        PlaybookVersionRef activeRef = versions.activeRef(workspaceId, selector)
                .orElseThrow(() -> conflict(
                        "no approved deployment topology scenario Playbook is active for "
                                + selector));
        ApprovedPlaybookVersion authority = versions.lockActiveApprovedByPlaybookId(
                        workspaceId, activeRef.playbookId())
                .orElseThrow(() -> conflict(
                        "the deployment topology scenario Playbook changed concurrently; retry"));
        if (!activeRef.equals(new PlaybookVersionRef(
                authority.playbookId(), authority.playbookVersion()))) {
            throw conflict(
                    "the deployment topology scenario Playbook changed concurrently; retry");
        }
        if (!"APPROVED".equals(authority.status())
                || !authority.playbook().operational()
                || !authority.playbookId().equals(authority.playbook().sopId())) {
            throw conflict(
                    "the deployment topology scenario Playbook is not an operational authority");
        }
        if (!policy.supportsRequiredProbe(authority, selector)) {
            throw conflict(
                    "the deployment topology scenario Playbook must require "
                            + DeploymentTopologyScenarioPolicy.TOOL_KEY
                            + " evidence for a "
                            + DeploymentTopologyScenarioPolicy.ASSET_TYPE
                            + " asset");
        }

        Instant observedReadyAt = clock.instant();
        Instant readyAt = observedReadyAt.isBefore(reportedAt)
                ? reportedAt : observedReadyAt;
        String correlationId = required(correlationIds.get(), "correlationId");
        ScenarioDiagnosisDraft draft = new ScenarioDiagnosisDraft(
                "diag-" + correlationId,
                "case-" + correlationId,
                "run-" + correlationId,
                safeIncident,
                DeploymentTopologyScenarioPolicy.SCENARIO_KEY,
                authority.playbook(),
                activeRef,
                safeActor,
                NorthStarTimings.concluded(reportedAt, readyAt, readyAt),
                rehearsal,
                TroubleshootingSafetyPolicy.EVIDENCE_IS_FIXTURE,
                List.of("部署拓扑拨测尚未执行；当前 Diagnosis 不输出根因或处置建议。"));
        return persistence.createOrGetForScenario(
                workspaceId,
                stateMachine.initializeScenarioAwaitingEvidence(draft),
                DeploymentTopologyScenarioPolicy.SCENARIO_KEY,
                reportedAt);
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " must not be blank");
        }
        return value.trim();
    }

    private MateClawException invalid(String message) {
        return new MateClawException(
                "err.troubleshooting.invalid_request", 400, message);
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.topology_scenario_conflict", 409, message);
    }
}
