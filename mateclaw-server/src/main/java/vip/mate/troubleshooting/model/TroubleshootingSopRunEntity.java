package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One SOP execution trace attached to a troubleshooting case.
 */
@Data
@TableName("mate_troubleshooting_sop_run")
public class TroubleshootingSopRunEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;

    private String caseId;

    private Long sopSkillId;

    private String sopName;

    private String sopVersion;

    private String domain;

    private String scenario;

    private Double confidence;

    /** pending / running / succeeded / evidence_insufficient / failed. */
    private String status;

    @TableField(value = "route_reason", updateStrategy = FieldStrategy.ALWAYS)
    private String routeReason;

    @TableField(value = "alert_json", updateStrategy = FieldStrategy.ALWAYS)
    private String alertJson;

    @TableField(value = "step_results_json", updateStrategy = FieldStrategy.ALWAYS)
    private String stepResultsJson;

    @TableField(value = "final_report_json", updateStrategy = FieldStrategy.ALWAYS)
    private String finalReportJson;

    @TableField(value = "validation_errors_json", updateStrategy = FieldStrategy.ALWAYS)
    private String validationErrorsJson;

    @TableField(value = "started_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime startedAt;

    @TableField(value = "completed_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime completedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
