package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Persistence shell for a review-only PlaybookKnowledgeRecord. */
@Data
@TableName("mate_troubleshooting_playbook_candidate")
public class TroubleshootingPlaybookCandidateEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String recordId;
    private String generationKey;
    private String sourceIncidentId;
    private String system;
    private String service;
    private String scenarioKey;
    private String origin;
    private String reviewStatus;
    private String validationStatus;
    private String contractVersion;
    private Boolean fixtureMode;
    private String aggregateJson;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
