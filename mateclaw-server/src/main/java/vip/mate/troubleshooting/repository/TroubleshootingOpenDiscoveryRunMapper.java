package vip.mate.troubleshooting.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.mate.troubleshooting.model.TroubleshootingOpenDiscoveryRunEntity;

@Mapper
public interface TroubleshootingOpenDiscoveryRunMapper
        extends BaseMapper<TroubleshootingOpenDiscoveryRunEntity> {

    @Select("""
            SELECT id, workspace_id, run_id, diagnosis_id,
                   visible_scenario_keys, selected_scenario_key,
                   selected_plan_fingerprint,
                   planned_signal_kinds, max_iterations, max_evidence_requests,
                   source_request_count, time_budget_ms, stop_reason,
                   evidence_refs, actor_ref,
                   formal_pilot_plan_version, source_acceptance_id,
                   source_binding_fingerprint, started_at, completed_at,
                   deleted, create_time, update_time
              FROM mate_troubleshooting_open_discovery_run
             WHERE workspace_id = #{workspaceId}
               AND diagnosis_id = #{diagnosisId}
               AND deleted = 0
             ORDER BY completed_at DESC, id DESC
             LIMIT 1
            """)
    TroubleshootingOpenDiscoveryRunEntity latestByDiagnosis(
            @Param("workspaceId") long workspaceId,
            @Param("diagnosisId") String diagnosisId);
}
