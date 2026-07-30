package vip.mate.troubleshooting.deployment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.TroubleshootingTopologyProbeRunEntity;
import vip.mate.troubleshooting.repository.TroubleshootingTopologyProbeRunMapper;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Diagnosis-scoped execution seam for the deployment-topology scenario.
 *
 * <p>The topology library remains an immutable Workspace asset store and the
 * existing analyzer remains the semantic read-only tool implementation. This
 * service only binds one execution and its safe projection to the authoritative
 * Diagnosis; it does not create a parallel diagnosis aggregate.</p>
 */
@Service
public class TopologyProbeEvidenceRunService {

    public static final String SCENARIO_KEY = "deployment_topology_probe";
    public static final String TOOL_KEY = "topology_synthetic_probe";
    private static final int MAX_LIST_LIMIT = 100;

    private final TroubleshootingPersistenceService persistence;
    private final DeploymentTopologyLibraryService library;
    private final TroubleshootingTopologyProbeRunMapper mapper;
    private final TopologyProbeEvidenceRunPersistenceService runPersistence;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public TopologyProbeEvidenceRunService(
            TroubleshootingPersistenceService persistence,
            DeploymentTopologyLibraryService library,
            TroubleshootingTopologyProbeRunMapper mapper,
            TopologyProbeEvidenceRunPersistenceService runPersistence,
            ObjectMapper objectMapper) {
        this(persistence, library, mapper, runPersistence, objectMapper, Clock.systemUTC());
    }

    TopologyProbeEvidenceRunService(
            TroubleshootingPersistenceService persistence,
            DeploymentTopologyLibraryService library,
            TroubleshootingTopologyProbeRunMapper mapper,
            TopologyProbeEvidenceRunPersistenceService runPersistence,
            ObjectMapper objectMapper,
            Clock clock) {
        this.persistence = persistence;
        this.library = library;
        this.mapper = mapper;
        this.runPersistence = runPersistence;
        this.objectMapper = objectMapper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public TopologyProbeEvidenceRun run(
            long workspaceId,
            String diagnosisId,
            String topologyId,
            String actorRef) {
        String safeDiagnosisId = safeText(diagnosisId, "diagnosisId", 128);
        String safeTopologyId = safeText(topologyId, "topologyId", 128);
        String safeActor = safeText(actorRef, "actorRef", 192);
        var diagnosis = persistence.get(workspaceId, safeDiagnosisId).diagnosis();
        if (diagnosis.status() == DiagnosisStatus.CLOSED) {
            throw new MateClawException(
                    "err.troubleshooting.topology_probe_diagnosis_closed",
                    409,
                    "closed diagnosis cannot accept new topology evidence");
        }

        Instant startedAt = Instant.now(clock);
        DeploymentTopologySopResult analysis = library.analyze(workspaceId, safeTopologyId);
        Instant completedAt = Instant.now(clock);
        DeploymentTopologySopResult persistedResult = persisted(analysis);
        TopologyProbeEvidenceRun run = new TopologyProbeEvidenceRun(
                "topology-run-" + UUID.randomUUID(),
                safeDiagnosisId,
                safeTopologyId,
                SCENARIO_KEY,
                TOOL_KEY,
                persistedResult,
                startedAt,
                completedAt,
                safeActor);

        TroubleshootingTopologyProbeRunEntity entity = new TroubleshootingTopologyProbeRunEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setRunId(run.runId());
        entity.setDiagnosisId(run.diagnosisId());
        entity.setTopologyId(run.topologyId());
        entity.setScenarioKey(run.scenarioKey());
        entity.setToolKey(run.toolKey());
        entity.setStatus(run.result().status().name());
        entity.setResultJson(write(run));
        entity.setActorRef(run.actorRef());
        entity.setStartedAt(LocalDateTime.ofInstant(startedAt, ZoneOffset.UTC));
        entity.setCompletedAt(LocalDateTime.ofInstant(completedAt, ZoneOffset.UTC));
        entity.setDeleted(0);
        entity.setCreateTime(LocalDateTime.ofInstant(completedAt, ZoneOffset.UTC));
        entity.setUpdateTime(LocalDateTime.ofInstant(completedAt, ZoneOffset.UTC));
        runPersistence.insertIfDiagnosisOpen(workspaceId, safeDiagnosisId, entity);
        return run;
    }

    public List<TopologyProbeEvidenceRun> list(
            long workspaceId,
            String diagnosisId,
            int limit) {
        String safeDiagnosisId = safeText(diagnosisId, "diagnosisId", 128);
        persistence.get(workspaceId, safeDiagnosisId);
        int boundedLimit = Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        return mapper.listByDiagnosis(workspaceId, safeDiagnosisId, boundedLimit).stream()
                .map(this::read)
                .toList();
    }

    private DeploymentTopologySopResult persisted(DeploymentTopologySopResult result) {
        return new DeploymentTopologySopResult(
                result.schemaVersion(),
                result.system(),
                result.systemLabel(),
                result.snapshotExportedAt(),
                result.signalKind(),
                result.status(),
                result.summary(),
                result.observations(),
                result.suspectLinks(),
                result.unconfiguredNodeKeys(),
                result.warnings(),
                result.completedAt(),
                result.modelCalled(),
                true);
    }

    private String write(TopologyProbeEvidenceRun run) {
        try {
            return objectMapper.writeValueAsString(run);
        } catch (JsonProcessingException failure) {
            throw new MateClawException(
                    "err.troubleshooting.topology_probe_persistence_failed",
                    500,
                    "topology evidence run cannot be serialized");
        }
    }

    private TopologyProbeEvidenceRun read(TroubleshootingTopologyProbeRunEntity entity) {
        try {
            TopologyProbeEvidenceRun run = objectMapper.readValue(
                    entity.getResultJson(), TopologyProbeEvidenceRun.class);
            if (!Objects.equals(entity.getDiagnosisId(), run.diagnosisId())
                    || !Objects.equals(entity.getRunId(), run.runId())
                    || !Objects.equals(entity.getTopologyId(), run.topologyId())) {
                throw invalidStoredRun();
            }
            return run;
        } catch (JsonProcessingException failure) {
            throw invalidStoredRun();
        }
    }

    private MateClawException invalidStoredRun() {
        return new MateClawException(
                "err.troubleshooting.topology_probe_invalid",
                500,
                "stored topology evidence run is invalid");
    }

    private String safeText(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > maxLength
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new MateClawException(
                    "err.troubleshooting.topology_probe_invalid",
                    400,
                    field + " must contain 1-" + maxLength + " display characters");
        }
        if (!TroubleshootingSecretRedactor.redact(normalized).equals(normalized)) {
            throw new MateClawException(
                    "err.troubleshooting.topology_probe_invalid",
                    400,
                    field + " must not contain credentials");
        }
        return normalized;
    }
}
