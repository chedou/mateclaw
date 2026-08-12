package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable revision of a workspace troubleshooting pilot plan. */
@Data
@TableName("mate_troubleshooting_pilot_plan")
public class TroubleshootingPilotPlanEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String planName;
    private String moduleScopes;
    private Long secondLineUserId;
    private Long thirdLineUserId;
    private Long sourceOwnerUserId;
    private Boolean enabled;
    private Integer version;
    private String changedBy;
    private String changeReason;
    private LocalDateTime createTime;
}
