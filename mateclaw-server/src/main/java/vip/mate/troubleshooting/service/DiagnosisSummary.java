package vip.mate.troubleshooting.service;

import vip.mate.troubleshooting.model.TroubleshootingDiagnosisEntity;

import java.time.LocalDateTime;

/**
 * Queue row for the console list.
 *
 * <p>Deliberately built from indexed columns only, never by parsing the stored
 * aggregate: a duty queue is read constantly, and deserializing every full
 * diagnosis to render a list would make the cheapest screen the most expensive
 * one. Whoever opens a row gets the whole aggregate from the detail endpoint.</p>
 */
public record DiagnosisSummary(
        String diagnosisId,
        String caseId,
        String system,
        String errorCode,
        String service,
        String status,
        boolean rehearsal,
        int version,
        LocalDateTime createTime,
        LocalDateTime updateTime) {

    public static DiagnosisSummary from(TroubleshootingDiagnosisEntity entity) {
        return new DiagnosisSummary(
                entity.getDiagnosisId(),
                entity.getCaseId(),
                entity.getSystem(),
                entity.getErrorCode(),
                entity.getService(),
                entity.getStatus(),
                Boolean.TRUE.equals(entity.getRehearsal()),
                entity.getVersion() == null ? 0 : entity.getVersion(),
                entity.getCreateTime(),
                entity.getUpdateTime());
    }
}
