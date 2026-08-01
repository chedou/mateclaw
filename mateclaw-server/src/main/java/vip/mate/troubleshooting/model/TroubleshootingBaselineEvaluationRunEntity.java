package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Persistence shell for one bounded T8 single-Agent baseline result. */
@Data
@TableName("mate_troubleshooting_baseline_eval_run")
public class TroubleshootingBaselineEvaluationRunEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String runId;
    private String runKey;
    private String sampleId;
    private String diagnosisId;
    private Integer sampleVersion;
    private String sourcePlatform;
    private Boolean evidenceFixtureMode;
    private Boolean diagnosisFixtureMode;
    private String runStatus;
    private String modelProvider;
    private String modelName;
    private String modelConfigVersion;
    private String claimToken;
    private LocalDateTime reservationExpiresAt;
    private Long modelDurationMs;
    private Long composedTotalMs;
    private String resultJson;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
}
