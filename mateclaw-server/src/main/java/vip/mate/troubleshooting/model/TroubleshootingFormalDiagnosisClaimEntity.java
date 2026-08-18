package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Durable direct-web reservation before a formal Diagnosis touches Guance. */
@Data
@TableName("mate_troubleshooting_formal_diagnosis_claim")
public class TroubleshootingFormalDiagnosisClaimEntity {

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
