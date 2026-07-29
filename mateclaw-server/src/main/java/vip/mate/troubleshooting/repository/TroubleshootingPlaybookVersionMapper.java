package vip.mate.troubleshooting.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import vip.mate.troubleshooting.model.TroubleshootingPlaybookVersionEntity;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TroubleshootingPlaybookVersionMapper
        extends BaseMapper<TroubleshootingPlaybookVersionEntity> {

    String COLUMNS = """
            id, workspace_id, playbook_id, selector_key, playbook_version,
            active_selector_key, system, error_code, service, status,
            source_origin, source_record_id, review_id, review_version,
            approved_by, approval_reason, approval_snapshot_json,
            deprecated_by, deprecation_reason, deprecated_at,
            contract_version, aggregate_json, version, deleted,
            create_time, update_time
            """;
    String SELECT_COLUMNS = "SELECT " + COLUMNS;

    @Select(SELECT_COLUMNS + """
              FROM mate_troubleshooting_playbook_version
             WHERE workspace_id = #{workspaceId}
               AND active_selector_key = #{selectorKey}
               AND status = 'APPROVED'
               AND deleted = 0
            """)
    TroubleshootingPlaybookVersionEntity findActive(
            @Param("workspaceId") long workspaceId,
            @Param("selectorKey") String selectorKey);

    @Select(SELECT_COLUMNS + """
              FROM mate_troubleshooting_playbook_version
             WHERE workspace_id = #{workspaceId}
               AND selector_key = #{selectorKey}
               AND deleted = 0
             ORDER BY playbook_version DESC
             LIMIT 1
            """)
    TroubleshootingPlaybookVersionEntity findCurrent(
            @Param("workspaceId") long workspaceId,
            @Param("selectorKey") String selectorKey);

    @Select(SELECT_COLUMNS + """
              FROM mate_troubleshooting_playbook_version
             WHERE workspace_id = #{workspaceId}
               AND review_id = #{reviewId}
               AND deleted = 0
            """)
    TroubleshootingPlaybookVersionEntity findByReview(
            @Param("workspaceId") long workspaceId,
            @Param("reviewId") String reviewId);

    @Select(SELECT_COLUMNS + """
              FROM mate_troubleshooting_playbook_version
             WHERE workspace_id = #{workspaceId}
               AND playbook_id = #{playbookId}
               AND deleted = 0
            """)
    TroubleshootingPlaybookVersionEntity findByPlaybookId(
            @Param("workspaceId") long workspaceId,
            @Param("playbookId") String playbookId);

    /** Selects the latest version per selector before applying registry filters. */
    @Select("""
            <script>
            SELECT pv.id, pv.workspace_id, pv.playbook_id, pv.selector_key,
                   pv.playbook_version, pv.active_selector_key, pv.system,
                   pv.error_code, pv.service, pv.status, pv.source_origin,
                   pv.source_record_id, pv.review_id, pv.review_version,
                   pv.approved_by, pv.approval_reason,
                   pv.approval_snapshot_json, pv.deprecated_by,
                   pv.deprecation_reason, pv.deprecated_at, pv.contract_version,
                   pv.aggregate_json, pv.version, pv.deleted,
                   pv.create_time, pv.update_time
              FROM mate_troubleshooting_playbook_version pv
             WHERE pv.workspace_id = #{workspaceId}
               AND pv.deleted = 0
               AND NOT EXISTS (
                    SELECT 1
                      FROM mate_troubleshooting_playbook_version newer
                     WHERE newer.workspace_id = pv.workspace_id
                       AND newer.selector_key = pv.selector_key
                       AND newer.deleted = 0
                       AND newer.playbook_version &gt; pv.playbook_version
               )
               <if test="status != null and status != ''">
                 AND pv.status = #{status}
               </if>
               <if test="system != null and system != ''">
                 AND pv.system = #{system}
               </if>
             ORDER BY pv.update_time DESC, pv.id DESC
             LIMIT #{limit}
            </script>
            """)
    List<TroubleshootingPlaybookVersionEntity> listLatest(
            @Param("workspaceId") long workspaceId,
            @Param("status") String status,
            @Param("system") String system,
            @Param("limit") int limit);

    @Select("""
            SELECT COALESCE(MAX(playbook_version), 0)
              FROM mate_troubleshooting_playbook_version
             WHERE workspace_id = #{workspaceId}
               AND selector_key = #{selectorKey}
               AND deleted = 0
            """)
    Integer maxPlaybookVersion(
            @Param("workspaceId") long workspaceId,
            @Param("selectorKey") String selectorKey);

    @Update("""
            UPDATE mate_troubleshooting_playbook_version
               SET status = 'DEPRECATED',
                   active_selector_key = NULL,
                   aggregate_json = #{aggregateJson},
                   deprecated_by = #{actor},
                   deprecation_reason = #{reason},
                   deprecated_at = #{now},
                   version = version + 1,
                   update_time = #{now}
             WHERE workspace_id = #{workspaceId}
               AND id = #{id}
               AND active_selector_key = #{selectorKey}
               AND status = 'APPROVED'
               AND version = #{expectedVersion}
               AND deleted = 0
            """)
    int retireActive(
            @Param("workspaceId") long workspaceId,
            @Param("id") long id,
            @Param("selectorKey") String selectorKey,
            @Param("expectedVersion") int expectedVersion,
            @Param("aggregateJson") String aggregateJson,
            @Param("actor") String actor,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now);
}
