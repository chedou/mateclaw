package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable approved Playbook artifact plus its replaceable active pointer. */
@Data
@TableName("mate_troubleshooting_playbook_version")
public class TroubleshootingPlaybookVersionEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String playbookId;
    private String selectorKey;
    private Integer playbookVersion;
    private String activeSelectorKey;
    private String systemName;
    private String errorCode;
    private String service;
    private String status;
    private String sourceOrigin;
    private String sourceRecordId;
    private String knowledgeEvidenceGrade;
    private String reviewId;
    private Integer reviewVersion;
    private String approvedBy;
    private String approvalReason;
    private String approvalSnapshotJson;
    private String deprecatedBy;
    private String deprecationReason;
    private LocalDateTime deprecatedAt;
    private String contractVersion;
    private String aggregateJson;
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
