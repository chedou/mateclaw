package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One immutable revision of a workspace-owned observability asset declaration. */
@Data
@TableName("mate_troubleshooting_observability_asset")
public class TroubleshootingObservabilityAssetEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String systemName;
    private String service;
    private String displayName;
    private String platform;
    private String environment;
    private String region;
    private String clusterName;
    private String namespaceName;
    private Boolean enabled;
    /** JSON object: canonical signal kind -> reviewed server binding reference. */
    private String signalBindings;
    /** JSON object containing only bounded, non-secret source-side resource identifiers. */
    private String assetParameters;
    private Integer version;
    private String changedBy;
    private String changeReason;
    private LocalDateTime createTime;

    public String getSystem() {
        return systemName;
    }

    public void setSystem(String system) {
        this.systemName = system;
    }
}
