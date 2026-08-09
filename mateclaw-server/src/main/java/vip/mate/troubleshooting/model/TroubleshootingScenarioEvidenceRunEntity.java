package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable, secret-free audit row for one scenario evidence-plan run. */
@Data
@TableName("mate_troubleshooting_scenario_evidence_run")
public class TroubleshootingScenarioEvidenceRunEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String runId;
    private String diagnosisId;
    private String playbookId;
    private Integer playbookVersion;
    private String diagnosisStatus;
    private String conclusionType;
    /** JSON array of frozen evidence request IDs only; no values or queries. */
    private String evidenceRefs;
    private String actorRef;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
