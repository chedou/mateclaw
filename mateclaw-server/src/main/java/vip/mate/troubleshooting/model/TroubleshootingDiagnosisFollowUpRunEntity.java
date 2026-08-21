package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Secret-free immutable ledger row for post-Diagnosis supplemental material. */
@Data
@TableName("mate_troubleshooting_diagnosis_follow_up_run")
public class TroubleshootingDiagnosisFollowUpRunEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String runId;
    private String diagnosisId;
    private Integer diagnosisVersion;
    private String conclusionType;
    private String turnKind;
    private Integer contentLength;
    private String disposition;
    private String actorRef;
    private LocalDateTime recordedAt;

    @TableLogic
    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
