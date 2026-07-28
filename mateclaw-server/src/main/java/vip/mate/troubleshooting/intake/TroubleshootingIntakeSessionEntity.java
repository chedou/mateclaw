package vip.mate.troubleshooting.intake;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Persistence shell for the immutable IntakeSession aggregate. */
@Data
@TableName("mate_troubleshooting_intake_session")
public class TroubleshootingIntakeSessionEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;
    private String intakeSessionId;

    @TableField(value = "active_key", updateStrategy = FieldStrategy.ALWAYS)
    private String activeKey;

    private String routingKey;
    private String source;
    private String conversationRef;

    @TableField(value = "delivery_conversation_id", updateStrategy = FieldStrategy.ALWAYS)
    private String deliveryConversationId;

    private String reporterRef;
    private String status;
    private LocalDateTime reportedAt;
    private LocalDateTime lastMessageAt;
    private String aggregateJson;
    private Integer version;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
