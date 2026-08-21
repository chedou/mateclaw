package vip.mate.troubleshooting.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.mate.troubleshooting.model.TroubleshootingDiagnosisFollowUpRunEntity;

import java.util.List;

@Mapper
public interface TroubleshootingDiagnosisFollowUpRunMapper
        extends BaseMapper<TroubleshootingDiagnosisFollowUpRunEntity> {

    @Select("""
            SELECT id, workspace_id, run_id, diagnosis_id, diagnosis_version,
                   conclusion_type, turn_kind, content_length,
                   disposition, actor_ref, recorded_at, deleted, create_time, update_time
              FROM mate_troubleshooting_diagnosis_follow_up_run
             WHERE workspace_id = #{workspaceId}
               AND diagnosis_id = #{diagnosisId}
               AND deleted = 0
             ORDER BY recorded_at DESC, id DESC
             LIMIT 100
            """)
    List<TroubleshootingDiagnosisFollowUpRunEntity> listByDiagnosis(
            @Param("workspaceId") long workspaceId,
            @Param("diagnosisId") String diagnosisId);
}
