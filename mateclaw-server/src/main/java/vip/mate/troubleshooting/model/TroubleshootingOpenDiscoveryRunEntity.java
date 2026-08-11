package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable, secret-free audit row for one bounded OPEN_DISCOVERY run. */
@Data
@TableName("mate_troubleshooting_open_discovery_run")
public class TroubleshootingOpenDiscoveryRunEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String runId;
    private String diagnosisId;
    private String visibleScenarioKeys;
    private String selectedScenarioKey;
    private String plannedSignalKinds;
    private Integer maxIterations;
    private Integer maxEvidenceRequests;
    private Integer sourceRequestCount;
    private Long timeBudgetMs;
    private String stopReason;
    /** JSON array of evidence request IDs only; no values or query text. */
    private String evidenceRefs;
    private String actorRef;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
