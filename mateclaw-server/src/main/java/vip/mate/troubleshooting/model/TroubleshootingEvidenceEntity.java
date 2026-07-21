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
 * Evidence item collected for one troubleshooting SOP run.
 */
@Data
@TableName("mate_troubleshooting_evidence")
public class TroubleshootingEvidenceEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;

    private String caseId;

    private Long runId;

    private String evidenceId;

    private String evidenceType;

    private String source;

    private String status;

    private String title;

    @TableField(value = "summary", updateStrategy = FieldStrategy.ALWAYS)
    private String summary;

    @TableField(value = "content_json", updateStrategy = FieldStrategy.ALWAYS)
    private String contentJson;

    @TableField(value = "collected_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime collectedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
