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
 * Configurable request template used by troubleshooting evidence connectors.
 */
@Data
@TableName("mate_troubleshooting_query_template")
public class TroubleshootingQueryTemplateEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;

    private String provider;

    private String evidenceType;

    private String templateKey;

    private String name;

    @TableField(value = "description", updateStrategy = FieldStrategy.ALWAYS)
    private String description;

    @TableField(value = "payload_template", updateStrategy = FieldStrategy.ALWAYS)
    private String payloadTemplate;

    @TableField(value = "dql_template", updateStrategy = FieldStrategy.ALWAYS)
    private String dqlTemplate;

    @TableField(value = "match_json", updateStrategy = FieldStrategy.ALWAYS)
    private String matchJson;

    private Integer enabled;

    private Integer defaultTemplate;

    private Integer priority;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
