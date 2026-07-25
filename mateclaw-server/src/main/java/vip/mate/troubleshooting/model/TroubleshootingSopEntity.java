package vip.mate.troubleshooting.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Authoritative deterministic SOP row; narrative Wiki pages are derived only. */
@Data
@TableName("mate_troubleshooting_sop")
public class TroubleshootingSopEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long workspaceId;
    private String sopId;
    private String routeKey;
    private String system;
    private String errorCode;
    private String service;
    private String status;
    private Boolean verified;
    private String contractVersion;
    private String aggregateJson;
    private Integer version;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
