package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable, workspace-scoped deployment topology snapshot. */
@Data
@TableName("mate_troubleshooting_deployment_topology")
public class TroubleshootingDeploymentTopologyEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String topologyId;
    private String name;
    private String systemName;
    private String systemLabel;
    private String schemaVersion;
    private LocalDateTime exportedAt;
    private String snapshotJson;
    private String snapshotFingerprint;
    private Integer nodeCount;
    private Integer linkCount;
    private Integer configuredProbeNodes;
    private String importedBy;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public String getSystem() {
        return systemName;
    }

    public void setSystem(String system) {
        this.systemName = system;
    }
}
