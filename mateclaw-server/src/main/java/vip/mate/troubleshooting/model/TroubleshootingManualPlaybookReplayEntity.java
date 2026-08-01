package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable safe projection of one manual Playbook candidate replay. */
@Data
@TableName("mate_troubleshooting_manual_playbook_replay")
public class TroubleshootingManualPlaybookReplayEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String attestationId;
    private String sourceRecordId;
    private String selectorKey;
    private String candidateFingerprint;
    private String suiteId;
    private Integer suiteVersion;
    private String suiteFingerprint;
    private String status;
    private String resultJson;
    private String executedBy;
    private LocalDateTime executedAt;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
