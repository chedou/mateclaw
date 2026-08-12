package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable unique-key reservation before any OPEN_DISCOVERY external call. */
@Data
@TableName("mate_troubleshooting_open_discovery_claim")
public class TroubleshootingOpenDiscoveryClaimEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String dedupKey;
    private String claimToken;
    private String status;
    private String diagnosisId;
    private LocalDateTime claimedAt;
    private LocalDateTime leaseExpiresAt;
    private LocalDateTime completedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
