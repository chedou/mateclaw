package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.TroubleshootingScenarioEvidenceRunEntity;
import vip.mate.troubleshooting.repository.TroubleshootingScenarioEvidenceRunMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Persists and reads immutable scenario evidence-run audit facts. */
@Service
public class ScenarioEvidenceRunAuditService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final TroubleshootingScenarioEvidenceRunMapper mapper;
    private final ObjectMapper objectMapper;

    public ScenarioEvidenceRunAuditService(
            TroubleshootingScenarioEvidenceRunMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public ScenarioEvidenceRunAudit insert(
            long workspaceId,
            ScenarioEvidenceRunAudit audit) {
        if (workspaceId <= 0 || audit == null) {
            throw invalid("workspaceId and evidence run audit are required");
        }
        TroubleshootingScenarioEvidenceRunEntity entity = entity(workspaceId, audit);
        if (mapper.insert(entity) != 1) {
            throw invalid("scenario evidence run audit could not be persisted");
        }
        return audit;
    }

    public Optional<ScenarioEvidenceRunAudit> latest(
            long workspaceId,
            String diagnosisId) {
        if (workspaceId <= 0 || diagnosisId == null || diagnosisId.isBlank()) {
            throw invalid("workspaceId and diagnosisId are required");
        }
        return Optional.ofNullable(
                        mapper.latestByDiagnosis(workspaceId, diagnosisId.trim()))
                .map(this::audit);
    }

    private TroubleshootingScenarioEvidenceRunEntity entity(
            long workspaceId,
            ScenarioEvidenceRunAudit audit) {
        TroubleshootingScenarioEvidenceRunEntity entity =
                new TroubleshootingScenarioEvidenceRunEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setRunId(audit.runId());
        entity.setDiagnosisId(audit.diagnosisId());
        entity.setPlaybookId(audit.playbookVersionRef().playbookId());
        entity.setPlaybookVersion(audit.playbookVersionRef().playbookVersion());
        entity.setDiagnosisStatus(audit.diagnosisStatus().name());
        entity.setConclusionType(audit.conclusionType().name());
        entity.setEvidenceRefs(writeEvidenceRefs(audit.evidenceRefs()));
        entity.setActorRef(audit.actorRef());
        entity.setStartedAt(LocalDateTime.ofInstant(audit.startedAt(), ZoneOffset.UTC));
        LocalDateTime completed = LocalDateTime.ofInstant(audit.completedAt(), ZoneOffset.UTC);
        entity.setCompletedAt(completed);
        entity.setDeleted(0);
        entity.setCreateTime(completed);
        entity.setUpdateTime(completed);
        return entity;
    }

    private ScenarioEvidenceRunAudit audit(
            TroubleshootingScenarioEvidenceRunEntity entity) {
        try {
            return new ScenarioEvidenceRunAudit(
                    entity.getRunId(),
                    entity.getDiagnosisId(),
                    new PlaybookVersionRef(
                            entity.getPlaybookId(), entity.getPlaybookVersion()),
                    DiagnosisStatus.valueOf(entity.getDiagnosisStatus()),
                    ConclusionType.valueOf(entity.getConclusionType()),
                    objectMapper.readValue(entity.getEvidenceRefs(), STRING_LIST),
                    entity.getStartedAt().toInstant(ZoneOffset.UTC),
                    entity.getCompletedAt().toInstant(ZoneOffset.UTC),
                    entity.getActorRef());
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException failure) {
            throw invalid("stored scenario evidence run audit is invalid");
        }
    }

    private String writeEvidenceRefs(List<String> refs) {
        try {
            return objectMapper.writeValueAsString(refs);
        } catch (JsonProcessingException failure) {
            throw invalid("scenario evidence references could not be serialized");
        }
    }

    private MateClawException invalid(String message) {
        return new MateClawException(
                "err.troubleshooting.scenario_evidence_audit_invalid", 500, message);
    }
}
