package vip.mate.troubleshooting.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.TroubleshootingOpenDiscoveryRunEntity;
import vip.mate.troubleshooting.repository.TroubleshootingOpenDiscoveryRunMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Persists and reads bounded OPEN_DISCOVERY run facts. */
@Service
public class OpenDiscoveryRunAuditService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final TroubleshootingOpenDiscoveryRunMapper mapper;
    private final ObjectMapper objectMapper;

    public OpenDiscoveryRunAuditService(
            TroubleshootingOpenDiscoveryRunMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public OpenDiscoveryRunAudit insert(long workspaceId, OpenDiscoveryRunAudit audit) {
        if (workspaceId <= 0 || audit == null) {
            throw invalid("workspaceId and open discovery run audit are required");
        }
        TroubleshootingOpenDiscoveryRunEntity entity = entity(workspaceId, audit);
        if (mapper.insert(entity) != 1) {
            throw invalid("open discovery run audit could not be persisted");
        }
        return audit;
    }

    public Optional<OpenDiscoveryRunAudit> latest(long workspaceId, String diagnosisId) {
        if (workspaceId <= 0 || diagnosisId == null || diagnosisId.isBlank()) {
            throw invalid("workspaceId and diagnosisId are required");
        }
        return Optional.ofNullable(mapper.latestByDiagnosis(workspaceId, diagnosisId.trim()))
                .map(this::audit);
    }

    private TroubleshootingOpenDiscoveryRunEntity entity(
            long workspaceId,
            OpenDiscoveryRunAudit audit) {
        TroubleshootingOpenDiscoveryRunEntity entity =
                new TroubleshootingOpenDiscoveryRunEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setRunId(audit.runId());
        entity.setDiagnosisId(audit.diagnosisId());
        entity.setVisibleScenarioKeys(writeList(audit.visibleScenarioKeys()));
        entity.setSelectedScenarioKey(audit.selectedScenarioKey());
        entity.setSelectedPlanFingerprint(audit.selectedPlanFingerprint());
        entity.setPlannedSignalKinds(writeList(audit.plannedSignalKinds()));
        entity.setMaxIterations(audit.maxIterations());
        entity.setMaxEvidenceRequests(audit.maxEvidenceRequests());
        entity.setSourceRequestCount(audit.sourceRequestCount());
        entity.setTimeBudgetMs(audit.timeBudget().toMillis());
        entity.setStopReason(audit.stopReason().name());
        entity.setEvidenceRefs(writeList(audit.evidenceRefs()));
        entity.setActorRef(audit.actorRef());
        entity.setStartedAt(LocalDateTime.ofInstant(audit.startedAt(), ZoneOffset.UTC));
        LocalDateTime completed = LocalDateTime.ofInstant(
                audit.completedAt(), ZoneOffset.UTC);
        entity.setCompletedAt(completed);
        entity.setDeleted(0);
        entity.setCreateTime(completed);
        entity.setUpdateTime(completed);
        return entity;
    }

    private OpenDiscoveryRunAudit audit(TroubleshootingOpenDiscoveryRunEntity entity) {
        try {
            return new OpenDiscoveryRunAudit(
                    entity.getRunId(),
                    entity.getDiagnosisId(),
                    readList(entity.getVisibleScenarioKeys()),
                    entity.getSelectedScenarioKey(),
                    entity.getSelectedPlanFingerprint(),
                    readList(entity.getPlannedSignalKinds()),
                    entity.getMaxIterations(),
                    entity.getMaxEvidenceRequests(),
                    entity.getSourceRequestCount(),
                    Duration.ofMillis(entity.getTimeBudgetMs()),
                    OpenDiscoveryRunAudit.StopReason.valueOf(entity.getStopReason()),
                    readList(entity.getEvidenceRefs()),
                    entity.getStartedAt().toInstant(ZoneOffset.UTC),
                    entity.getCompletedAt().toInstant(ZoneOffset.UTC),
                    entity.getActorRef());
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException failure) {
            throw invalid("stored open discovery run audit is invalid");
        }
    }

    private List<String> readList(String value) throws JsonProcessingException {
        return objectMapper.readValue(value, STRING_LIST);
    }

    private String writeList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException failure) {
            throw invalid("open discovery run references could not be serialized");
        }
    }

    private MateClawException invalid(String message) {
        return new MateClawException(
                "err.troubleshooting.open_discovery_audit_invalid", 500, message);
    }
}
