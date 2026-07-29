package vip.mate.troubleshooting.service;

import vip.mate.troubleshooting.model.TroubleshootingSopEntity;
import vip.mate.troubleshooting.model.TroubleshootingPlaybookVersionEntity;

import java.time.LocalDateTime;

/**
 * Registry row for browsing the knowledge base.
 *
 * <p>Carries {@code status} and {@code verified} together because only their
 * conjunction makes a SOP operational — a reviewer scanning the list needs to
 * see at a glance which entries can actually drive a diagnosis and which are
 * still drafts producing shadow results.</p>
 */
public record SopSummary(
        String sopId,
        String routeKey,
        String system,
        String errorCode,
        String service,
        String status,
        boolean verified,
        boolean operational,
        LocalDateTime createTime,
        LocalDateTime updateTime,
        Integer playbookVersion,
        String sourceOrigin,
        String sourceRecordId,
        String reviewId,
        Integer reviewVersion) {

    /** Compatibility constructor for legacy registry rows and existing callers. */
    public SopSummary(
            String sopId,
            String routeKey,
            String system,
            String errorCode,
            String service,
            String status,
            boolean verified,
            boolean operational,
            LocalDateTime createTime,
            LocalDateTime updateTime) {
        this(
                sopId, routeKey, system, errorCode, service, status,
                verified, operational, createTime, updateTime,
                null, null, null, null, null);
    }

    public static SopSummary from(TroubleshootingSopEntity entity) {
        boolean verified = Boolean.TRUE.equals(entity.getVerified());
        return new SopSummary(
                entity.getSopId(),
                entity.getRouteKey(),
                entity.getSystem(),
                entity.getErrorCode(),
                entity.getService(),
                entity.getStatus(),
                verified,
                verified && "approved".equals(entity.getStatus()),
                entity.getCreateTime(),
                entity.getUpdateTime());
    }

    public static SopSummary from(TroubleshootingPlaybookVersionEntity entity) {
        boolean operational = "APPROVED".equals(entity.getStatus())
                && entity.getActiveSelectorKey() != null;
        return new SopSummary(
                entity.getPlaybookId(),
                entity.getSelectorKey(),
                entity.getSystem(),
                entity.getErrorCode(),
                entity.getService(),
                entity.getStatus().toLowerCase(java.util.Locale.ROOT),
                operational,
                operational,
                entity.getCreateTime(),
                entity.getUpdateTime(),
                entity.getPlaybookVersion(),
                entity.getSourceOrigin(),
                entity.getSourceRecordId(),
                entity.getReviewId(),
                entity.getReviewVersion());
    }
}
