package vip.mate.troubleshooting.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.mate.troubleshooting.model.TroubleshootingPilotPlanEntity;

@Mapper
public interface TroubleshootingPilotPlanMapper
        extends BaseMapper<TroubleshootingPilotPlanEntity> {

    @Select("""
            SELECT id, workspace_id, plan_name, module_scopes,
                   second_line_user_id, third_line_user_id, source_owner_user_id,
                   enabled, version, changed_by, change_reason, create_time
              FROM mate_troubleshooting_pilot_plan
             WHERE workspace_id = #{workspaceId}
             ORDER BY version DESC
             LIMIT 1
            """)
    TroubleshootingPilotPlanEntity findLatestByWorkspace(
            @Param("workspaceId") long workspaceId);
}
