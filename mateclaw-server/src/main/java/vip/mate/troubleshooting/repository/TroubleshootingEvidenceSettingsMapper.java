package vip.mate.troubleshooting.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import vip.mate.troubleshooting.model.TroubleshootingEvidenceSettingsEntity;

@Mapper
public interface TroubleshootingEvidenceSettingsMapper
        extends BaseMapper<TroubleshootingEvidenceSettingsEntity> {

    @Select("""
            SELECT workspace_id, guance_enabled, guance_base_url, guance_api_key,
                   guance_allow_insecure_http, replay_enabled, agent_enabled,
                   version, changed_by, change_reason, create_time, update_time
              FROM mate_troubleshooting_evidence_settings
             WHERE workspace_id = #{workspaceId}
             LIMIT 1
            """)
    TroubleshootingEvidenceSettingsEntity findByWorkspace(@Param("workspaceId") long workspaceId);

    /**
     * Compare-and-set on {@code version}.
     *
     * <p>The version predicate is what makes a concurrent credential edit fail
     * loudly instead of one owner's key silently overwriting another's. A
     * return of 0 means someone else wrote first; the caller must re-read
     * rather than retry blindly.
     */
    @Update("""
            UPDATE mate_troubleshooting_evidence_settings
               SET guance_enabled = #{e.guanceEnabled},
                   guance_base_url = #{e.guanceBaseUrl},
                   guance_api_key = #{e.guanceApiKey},
                   guance_allow_insecure_http = #{e.guanceAllowInsecureHttp},
                   replay_enabled = #{e.replayEnabled},
                   agent_enabled = #{e.agentEnabled},
                   version = #{nextVersion},
                   changed_by = #{e.changedBy},
                   change_reason = #{e.changeReason},
                   update_time = CURRENT_TIMESTAMP
             WHERE workspace_id = #{e.workspaceId}
               AND version = #{expectedVersion}
            """)
    int updateIfVersionMatches(@Param("e") TroubleshootingEvidenceSettingsEntity entity,
                               @Param("expectedVersion") int expectedVersion,
                               @Param("nextVersion") int nextVersion);
}
