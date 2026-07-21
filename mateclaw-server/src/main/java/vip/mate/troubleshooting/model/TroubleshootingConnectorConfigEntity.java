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
 * Workspace-scoped connection settings for troubleshooting evidence providers.
 */
@Data
@TableName("mate_troubleshooting_connector_config")
public class TroubleshootingConnectorConfigEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;

    private String provider;

    private String name;

    private String baseUrl;

    private String syntheticsPath;

    private String metricsPath;

    @TableField(value = "token", updateStrategy = FieldStrategy.ALWAYS)
    private String token;

    private String tokenHeader;

    @TableField(value = "token_prefix", updateStrategy = FieldStrategy.ALWAYS)
    private String tokenPrefix;

    private String timeWindow;

    private Integer syntheticsLimit;

    private String metricsWindow;

    private Integer metricsLimit;

    private Integer maxResponseChars;

    private Integer enabled;

    private Integer defaultConfig;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private Integer deleted;
}
