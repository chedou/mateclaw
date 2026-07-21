package vip.mate.loop.model;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One Loop Engineering run. Execution is intentionally separated from
 * troubleshooting SOP runs even though both keep step evidence and reports.
 */
@Data
@TableName("mate_loop_run")
public class LoopRunEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;

    private Long superpowerSkillId;

    private String superpowerName;

    private String superpowerVersion;

    private String domain;

    private String scenario;

    /** planned / running / succeeded / needs_human / failed. */
    private String status;

    @TableField(value = "input_json", updateStrategy = FieldStrategy.ALWAYS)
    private String inputJson;

    @TableField(value = "step_results_json", updateStrategy = FieldStrategy.ALWAYS)
    private String stepResultsJson;

    @TableField(value = "artifacts_json", updateStrategy = FieldStrategy.ALWAYS)
    private String artifactsJson;

    @TableField(value = "final_report_json", updateStrategy = FieldStrategy.ALWAYS)
    private String finalReportJson;

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
