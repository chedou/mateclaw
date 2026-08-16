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
            active_selector_key, system_name, error_code, service, status,
            source_origin, source_record_id, knowledge_evidence_grade,
            review_id, review_version,
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

    /** Locks the still-active authority until the enclosing Diagnosis transaction commits. */
    @Select(SELECT_COLUMNS + """
              FROM mate_troubleshooting_playbook_version
             WHERE workspace_id = #{workspaceId}
               AND playbook_id = #{playbookId}
               AND active_selector_key = selector_key
               AND status = 'APPROVED'
               AND deleted = 0
             FOR UPDATE
            """)
    TroubleshootingPlaybookVersionEntity lockActiveApprovedByPlaybookId(
            @Param("workspaceId") long workspaceId,
            @Param("playbookId") String playbookId);

    /** Selects the latest version per selector before applying registry filters. */
    @Select("""
            <script>
            SELECT pv.id, pv.workspace_id, pv.playbook_id, pv.selector_key,
                   pv.playbook_version, pv.active_selector_key, pv.system_name,
                   pv.error_code, pv.service, pv.status, pv.source_origin,
                   pv.source_record_id, pv.knowledge_evidence_grade,
                   pv.review_id, pv.review_version,
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
                 AND pv.system_name = #{system}
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

    /**
     * At most two live systems for an exact service/error-code selector.
     * Two rows are enough to prove ambiguity without scanning the registry.
     */
    @Select("""
            SELECT DISTINCT system_name
              FROM mate_troubleshooting_playbook_version
             WHERE workspace_id = #{workspaceId}
               AND service = #{service}
               AND error_code = #{errorCode}
               AND active_selector_key = selector_key
               AND status = 'APPROVED'
               AND deleted = 0
             ORDER BY system_name ASC
             LIMIT 2
            """)
    List<String> listActiveSystemsForExactRoute(
            @Param("workspaceId") long workspaceId,
            @Param("service") String service,
            @Param("errorCode") String errorCode);

    /**
     * At most two live systems for a service, regardless of error code.
     *
     * <p>Monitoring alerts routinely name a service without carrying an error
     * code. Two rows are still enough to prove ambiguity, and two rows must
     * leave the system unresolved: a service owned by more than one system is
     * exactly the case where guessing would hand deterministic authority to
     * the wrong Playbook.</p>
     */
    @Select("""
            SELECT DISTINCT system_name
              FROM mate_troubleshooting_playbook_version
             WHERE workspace_id = #{workspaceId}
               AND service = #{service}
               AND active_selector_key = selector_key
               AND status = 'APPROVED'
               AND deleted = 0
             ORDER BY system_name ASC
             LIMIT 2
            """)
    List<String> listActiveSystemsForService(
            @Param("workspaceId") long workspaceId,
            @Param("service") String service);

    @Select(SELECT_COLUMNS + """
              FROM mate_troubleshooting_playbook_version
             WHERE source_origin = 'MANUAL'
               AND knowledge_evidence_grade = 'UNVERIFIED'
               AND deleted = 0
               AND id > #{afterId}
             ORDER BY id ASC
             LIMIT #{limit}
            """)
    List<TroubleshootingPlaybookVersionEntity> listUnverifiedKnowledgeEvidenceGradesAfter(
            @Param("afterId") long afterId,
            @Param("limit") int limit);

    @Update("""
            UPDATE mate_troubleshooting_playbook_version
               SET knowledge_evidence_grade = #{grade}
             WHERE id = #{id}
               AND source_origin = 'MANUAL'
               AND knowledge_evidence_grade = 'UNVERIFIED'
               AND deleted = 0
            """)
    int backfillKnowledgeEvidenceGrade(
            @Param("id") long id,
            @Param("grade") String grade);

    /**
     * Active selectors under one prefix, so a refusal can name what does exist.
     *
     * <p>Scoped to the workspace and bounded by {@code limit}: listing the
     * neighbourhood only helps if it stays short and cannot reach another
     * tenant's registry.</p>
     */
    @Select("""
            SELECT selector_key
              FROM mate_troubleshooting_playbook_version
             WHERE workspace_id = #{workspaceId}
               AND active_selector_key = selector_key
               AND status = 'APPROVED'
               AND deleted = 0
               AND selector_key LIKE #{prefixPattern} ESCAPE '\\'
             ORDER BY selector_key
             LIMIT #{limit}
            """)
    List<String> listActiveSelectorsLike(
            @Param("workspaceId") long workspaceId,
            @Param("prefixPattern") String prefixPattern,
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
