package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Idempotency ledger that points at the ordinary MySQL chat message rows. */
@Data
@TableName("mate_troubleshooting_chat_turn")
public class TroubleshootingChatTurnEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String conversationId;
    private String clientTurnId;
    private Long agentId;
    private Long userMessageId;
    private Long assistantMessageId;
    @TableLogic
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
