package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One workspace's declaration of where a signal kind is collected from.
 *
 * <p>{@code platforms} 存的是逗号分隔的有序平台名，**不存端点也不存凭据**——那些
 * 属于运维配置的适配器。租户只能在已启用的源之间做选择，不能引入一个新的源。</p>
 */
@Data
@TableName("mate_troubleshooting_evidence_route")
public class TroubleshootingEvidenceRouteEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String systemName;
    private String signalKind;
    /** Ordered, comma-separated platform names. Empty string = collect nothing here. */
    private String platforms;
    private String updatedBy;
    private String reason;
    private Integer version;

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
