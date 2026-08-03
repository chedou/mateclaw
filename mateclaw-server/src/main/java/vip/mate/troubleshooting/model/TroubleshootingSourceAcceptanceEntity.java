package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Platform-agnostic owner acceptance row; carries no credential or query text. */
@Data
@TableName("mate_troubleshooting_source_acceptance")
public class TroubleshootingSourceAcceptanceEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String acceptanceId;
    private String platform;
    private String bindingFingerprint;
    private String aggregateJson;
    private Integer version;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
