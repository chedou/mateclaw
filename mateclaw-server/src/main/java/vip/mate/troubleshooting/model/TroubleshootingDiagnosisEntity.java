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
    private Integer version;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
