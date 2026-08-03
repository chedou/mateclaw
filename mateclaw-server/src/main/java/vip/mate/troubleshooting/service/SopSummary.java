package vip.mate.troubleshooting.service;

import vip.mate.troubleshooting.model.TroubleshootingSopEntity;
import vip.mate.troubleshooting.model.TroubleshootingPlaybookVersionEntity;
import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;

import java.time.LocalDateTime;

/**
 * Registry row for browsing the knowledge base.
 *
 * <p>Carries {@code status} and {@code verified} together because only their
 * conjunction makes a SOP operational — a reviewer scanning the list needs to
 * see at a glance which entries can actually drive a diagnosis and which are
 * still drafts producing shadow results.</p>
 *
 * <p><b>{@code sopId} 装的是两个身份空间的值</b>，取决于这一行来自哪张表：注册行
 * 装人工来源记录号（评审那几个接口收的就是它），已生效的版本行装版本表的
 * {@code playbook-*}。{@link TroubleshootingSopPersistenceService#list} 会用版本行
 * 覆盖同一 selector 的注册行，所以同一张列表里两种都会出现。</p>
 *
 * <p>这件事曾经把详情接口弄坏过：{@code by-id} 只查注册表，于是列表发出去的
 * 已生效行 id 一律 404——**恰好是最重要的那些行打不开**。现在 {@code by-id} 两种
 * 身份都认；要拿人工来源号请读 {@code sourceRecordId}（版本行才有，注册行的
 * {@code sopId} 本身就是）。</p>
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
        Integer reviewVersion,
        KnowledgeEvidenceGrade knowledgeEvidenceGrade) {

    public SopSummary {
        knowledgeEvidenceGrade = knowledgeEvidenceGrade == null
                ? KnowledgeEvidenceGrade.UNVERIFIED
                : knowledgeEvidenceGrade;
    }

    /** Compatibility constructor for the old versioned list shape. */
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
            LocalDateTime updateTime,
            Integer playbookVersion,
            String sourceOrigin,
            String sourceRecordId,
            String reviewId,
            Integer reviewVersion) {
        this(
                sopId, routeKey, system, errorCode, service, status,
                verified, operational, createTime, updateTime, playbookVersion,
                sourceOrigin, sourceRecordId, reviewId, reviewVersion,
                KnowledgeEvidenceGrade.UNVERIFIED);
    }

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
                null, null, null, null, null, KnowledgeEvidenceGrade.UNVERIFIED);
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
                entity.getUpdateTime(),
                null, null, null, null, null,
                KnowledgeEvidenceGrade.UNVERIFIED);
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
                entity.getReviewVersion(),
                KnowledgeEvidenceGrade.fromStored(entity.getKnowledgeEvidenceGrade()));
    }
}
