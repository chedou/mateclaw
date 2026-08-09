package vip.mate.troubleshooting.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.mate.troubleshooting.model.TroubleshootingScenarioEvidenceRunEntity;

@Mapper
public interface TroubleshootingScenarioEvidenceRunMapper
        extends BaseMapper<TroubleshootingScenarioEvidenceRunEntity> {

    @Select("""
            SELECT id, workspace_id, run_id, diagnosis_id, playbook_id,
                   playbook_version, diagnosis_status, conclusion_type,
                   evidence_refs, actor_ref, started_at, completed_at,
                   deleted, create_time, update_time
              FROM mate_troubleshooting_scenario_evidence_run
             WHERE workspace_id = #{workspaceId}
               AND diagnosis_id = #{diagnosisId}
               AND deleted = 0
             ORDER BY completed_at DESC, id DESC
             LIMIT 1
            """)
    TroubleshootingScenarioEvidenceRunEntity latestByDiagnosis(
            @Param("workspaceId") long workspaceId,
            @Param("diagnosisId") String diagnosisId);
}
