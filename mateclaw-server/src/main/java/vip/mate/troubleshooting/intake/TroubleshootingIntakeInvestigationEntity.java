package vip.mate.troubleshooting.intake;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable lease and retry state for one READY intake investigation. */
@Data
@TableName("mate_troubleshooting_intake_investigation")
public class TroubleshootingIntakeInvestigationEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;
    private String intakeSessionId;

    @TableField(value = "diagnosis_id", updateStrategy = FieldStrategy.ALWAYS)
    private String diagnosisId;

    private IntakeInvestigationStatus status;
    private Integer attempts;
    private Integer terminalAttempts;

    @TableField(value = "next_attempt_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime nextAttemptAt;

    @TableField(value = "claimed_by", updateStrategy = FieldStrategy.ALWAYS)
    private String claimedBy;

    @TableField(value = "lease_expires_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime leaseExpiresAt;

    @TableField(value = "last_error", updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;

    @TableField(value = "completed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime completedAt;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
