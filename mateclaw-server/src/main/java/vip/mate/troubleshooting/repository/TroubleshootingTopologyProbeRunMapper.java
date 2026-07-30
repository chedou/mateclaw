package vip.mate.troubleshooting.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.mate.troubleshooting.model.TroubleshootingTopologyProbeRunEntity;

import java.util.List;

@Mapper
public interface TroubleshootingTopologyProbeRunMapper
        extends BaseMapper<TroubleshootingTopologyProbeRunEntity> {

    @Select("""
            SELECT id, workspace_id, run_id, diagnosis_id, topology_id,
                   scenario_key, tool_key, status, result_json, actor_ref,
                   started_at, completed_at, deleted, create_time, update_time
              FROM mate_troubleshooting_topology_probe_run
             WHERE workspace_id = #{workspaceId}
               AND diagnosis_id = #{diagnosisId}
               AND deleted = 0
             ORDER BY completed_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<TroubleshootingTopologyProbeRunEntity> listByDiagnosis(
            @Param("workspaceId") long workspaceId,
            @Param("diagnosisId") String diagnosisId,
            @Param("limit") int limit);
}
