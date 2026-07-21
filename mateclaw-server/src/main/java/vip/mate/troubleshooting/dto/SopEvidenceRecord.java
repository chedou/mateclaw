package vip.mate.troubleshooting.dto;

import vip.mate.troubleshooting.model.TroubleshootingEvidenceEntity;

import java.time.LocalDateTime;

public record SopEvidenceRecord(
        Long id,
        String evidenceId,
        String evidenceType,
        String source,
        String status,
        String title,
        String summary,
        String contentJson,
        LocalDateTime collectedAt
) {
    public static SopEvidenceRecord from(TroubleshootingEvidenceEntity entity) {
        return new SopEvidenceRecord(
                entity.getId(),
                entity.getEvidenceId(),
                entity.getEvidenceType(),
                entity.getSource(),
                entity.getStatus(),
                entity.getTitle(),
                entity.getSummary(),
                entity.getContentJson(),
                entity.getCollectedAt()
        );
    }
}
