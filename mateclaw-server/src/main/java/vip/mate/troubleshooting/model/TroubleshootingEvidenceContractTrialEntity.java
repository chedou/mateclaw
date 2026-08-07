package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable, secret-free audit row for one admin evidence-contract trial. */
@Data
@TableName("mate_troubleshooting_evidence_contract_trial")
public class TroubleshootingEvidenceContractTrialEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String trialId;
    private Long workspaceId;
    private String system;
    private String service;
    private String contractRef;
    private String signalKind;
    private String assetId;
    private Integer assetVersion;
    private String status;
    private String stopReason;
    private String sourcePlatform;
    /** JSON array containing canonical field names only, never values. */
    private String observedFields;
    private Long durationMs;
    private String actor;
    private LocalDateTime completedAt;
    private LocalDateTime createTime;
}
