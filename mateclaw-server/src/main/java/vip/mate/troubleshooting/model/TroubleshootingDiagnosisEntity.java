package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Persistence shell for the immutable {@link Diagnosis} aggregate. */
@Data
@TableName("mate_troubleshooting_diagnosis")
public class TroubleshootingDiagnosisEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;
    private String diagnosisId;
    private String caseId;
    private String runId;
    private String system;

    @TableField(value = "error_code", updateStrategy = FieldStrategy.ALWAYS)
    private String errorCode;

    private String service;

    @TableField(value = "dedup_key", updateStrategy = FieldStrategy.ALWAYS)
    private String dedupKey;

    @TableField(value = "source_intake_session_id", updateStrategy = FieldStrategy.ALWAYS)
    private String sourceIntakeSessionId;

    private Boolean rehearsal;
    private String status;
    private String contractVersion;
    private String aggregateJson;

    @TableField(value = "investigation_mode", updateStrategy = FieldStrategy.ALWAYS)
    private String investigationMode;

    @TableField(value = "route_authority", updateStrategy = FieldStrategy.ALWAYS)
    private String routeAuthority;

    private Integer version;

    private String closureNotificationStatus;
    private Integer closureNotificationAttempts;

    @TableField(value = "closure_notification_claimed_by", updateStrategy = FieldStrategy.ALWAYS)
    private String closureNotificationClaimedBy;

    @TableField(value = "closure_notification_lease_expires_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime closureNotificationLeaseExpiresAt;

    @TableField(value = "closure_notification_next_attempt_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime closureNotificationNextAttemptAt;

    @TableField(value = "closure_notification_last_error", updateStrategy = FieldStrategy.ALWAYS)
    private String closureNotificationLastError;

    @TableField(value = "closure_notification_completed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime closureNotificationCompletedAt;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
