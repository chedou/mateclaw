package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Persistence shell for a secret-free T8 evaluation sample aggregate. */
@Data
@TableName("mate_troubleshooting_evaluation_sample")
public class TroubleshootingEvidenceEvaluationSampleEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String sampleId;
    private String sampleKey;
    private String captureIdentityKey;
    private Integer captureRevision;
    private String diagnosisId;
    private String system;
    private String service;
    private String scenarioKey;
    private String sourcePlatform;
    private String evidenceStage;
    private String referenceStatus;
    private Boolean fixtureMode;
    private Boolean diagnosisFixtureMode;
    private String aggregateJson;
    private Integer version;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
