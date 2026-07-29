package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Persistence shell for one workspace-scoped knowledge review decision. */
@Data
@TableName("mate_troubleshooting_knowledge_review")
public class TroubleshootingKnowledgeReviewEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String reviewId;
    private String origin;
    private String sourceRecordId;
    private String selectorKey;
    private String status;
    private String reviewer;
    private String reason;
    private String snapshotJson;
    private Integer version;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
