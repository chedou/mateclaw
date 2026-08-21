package vip.mate.troubleshooting.followup;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.TroubleshootingDiagnosisFollowUpRunEntity;
import vip.mate.troubleshooting.repository.TroubleshootingDiagnosisFollowUpRunMapper;
import vip.mate.troubleshooting.repository.TroubleshootingDiagnosisMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Insert-only persistence for supplemental investigation receipts. */
@Service
public class DiagnosisFollowUpRunStore {

    private final TroubleshootingDiagnosisFollowUpRunMapper mapper;
    private final TroubleshootingDiagnosisMapper diagnosisMapper;

    public DiagnosisFollowUpRunStore(
            TroubleshootingDiagnosisFollowUpRunMapper mapper,
            TroubleshootingDiagnosisMapper diagnosisMapper) {
        this.mapper = mapper;
        this.diagnosisMapper = diagnosisMapper;
    }

    @Transactional
    public DiagnosisFollowUpRun insert(long workspaceId, DiagnosisFollowUpRun run) {
        if (workspaceId <= 0 || run == null) {
            throw invalid("workspaceId and follow-up run are required");
        }
        Integer lockedVersion = diagnosisMapper.lockVersionForFollowUpAppend(
                workspaceId, run.diagnosisId());
        if (lockedVersion == null) {
            throw new MateClawException(
                    "err.troubleshooting.diagnosis_not_found", 404,
                    "troubleshooting diagnosis not found: " + run.diagnosisId());
        }
        if (lockedVersion != run.diagnosisVersion()) {
            throw new MateClawException(
                    "err.troubleshooting.follow_up_stale", 409,
                    "diagnosis changed before the follow-up run could be appended");
        }
        TroubleshootingDiagnosisFollowUpRunEntity row = new TroubleshootingDiagnosisFollowUpRunEntity();
        row.setWorkspaceId(workspaceId);
        row.setRunId(run.runId());
        row.setDiagnosisId(run.diagnosisId());
        row.setDiagnosisVersion(run.diagnosisVersion());
        row.setConclusionType(run.conclusionType().name());
        row.setTurnKind(run.turnKind().name());
        row.setContentLength(run.contentLength());
        row.setDisposition(run.disposition().name());
        row.setActorRef(run.actorRef());
        LocalDateTime recordedAt = LocalDateTime.ofInstant(run.recordedAt(), ZoneOffset.UTC);
        row.setRecordedAt(recordedAt);
        row.setDeleted(0);
        row.setCreateTime(recordedAt);
        row.setUpdateTime(recordedAt);
        if (mapper.insert(row) != 1) {
            throw invalid("follow-up run could not be persisted");
        }
        return run;
    }

    public List<DiagnosisFollowUpRun> list(long workspaceId, String diagnosisId) {
        if (workspaceId <= 0 || diagnosisId == null || diagnosisId.isBlank()) {
            throw invalid("workspaceId and diagnosisId are required");
        }
        return mapper.listByDiagnosis(workspaceId, diagnosisId.trim()).stream()
                .map(this::toDomain)
                .toList();
    }

    private DiagnosisFollowUpRun toDomain(TroubleshootingDiagnosisFollowUpRunEntity row) {
        try {
            return new DiagnosisFollowUpRun(
                    row.getRunId(),
                    row.getDiagnosisId(),
                    row.getDiagnosisVersion(),
                    ConclusionType.valueOf(row.getConclusionType()),
                    DiagnosisFollowUpIntent.valueOf(row.getTurnKind()),
                    row.getContentLength(),
                    DiagnosisFollowUpDisposition.valueOf(row.getDisposition()),
                    row.getActorRef(),
                    row.getRecordedAt().toInstant(ZoneOffset.UTC));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw invalid("stored follow-up run is invalid");
        }
    }

    private MateClawException invalid(String message) {
        return new MateClawException(
                "err.troubleshooting.follow_up_run_invalid", 500, message);
    }
}
