package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Immutable evidence-run row linked to one troubleshooting Diagnosis. */
@Data
@TableName("mate_troubleshooting_topology_probe_run")
public class TroubleshootingTopologyProbeRunEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String runId;
    private String diagnosisId;
    private String topologyId;
    private String scenarioKey;
    private String toolKey;
    private String status;
    private String resultJson;
    private String actorRef;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
