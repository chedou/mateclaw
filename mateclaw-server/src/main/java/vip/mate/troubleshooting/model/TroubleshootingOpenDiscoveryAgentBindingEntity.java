package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Workspace binding of the dedicated OPEN_DISCOVERY digital employee (Agent). */
@Data
@TableName("mate_troubleshooting_open_discovery_agent_binding")
public class TroubleshootingOpenDiscoveryAgentBindingEntity {

    @TableId
    private Long workspaceId;
    private Long agentId;
    private String boundBy;
    private LocalDateTime boundAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
