package vip.mate.troubleshooting.intake;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Idempotency receipt; raw message content is deliberately never stored here. */
@Data
@TableName("mate_troubleshooting_intake_message")
public class TroubleshootingIntakeMessageReceiptEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;
    private String source;
    private String sourceMessageId;
    private String intakeSessionId;
    private LocalDateTime receivedAt;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
}
