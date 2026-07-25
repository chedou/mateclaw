package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Transactional outbox envelope for one knowledge candidate publication. */
@Data
@TableName("mate_troubleshooting_knowledge_outbox")
public class TroubleshootingKnowledgeOutboxEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;
    private String publicationId;
    private String diagnosisId;
    private String candidateId;
    private String eventType;
    private String contractVersion;
    private String payloadJson;
    private KnowledgePublicationStatus status;
    private Integer attempts;

    @TableField(value = "last_error", updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;

    @TableField(value = "claimed_by", updateStrategy = FieldStrategy.ALWAYS)
    private String claimedBy;

    @TableField(value = "lease_expires_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime leaseExpiresAt;

    @TableField(value = "published_at", updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime publishedAt;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
