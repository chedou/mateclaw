package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One immutable revision of a workspace-managed evidence contract (method body). */
@Data
@TableName("mate_troubleshooting_evidence_contract")
public class TroubleshootingEvidenceContractEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long workspaceId;
    private String contractRef;
    private String signalKind;
    /** GENERIC | SYSTEM | MODULE */
    private String scopeType;
    private String systemName;
    private String serviceName;
    private String scenario;
    private String question;
    private String summary;
    private String namespace;
    private Integer maxRows;
    private String queryTemplate;
    private String fixedConditionsJson;
    private String assetParametersJson;
    private String fieldAliasesJson;
    private Integer enabled;
    private Integer version;
    private String changedBy;
    private String changeReason;
    private LocalDateTime createTime;
}
