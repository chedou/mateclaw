package vip.mate.troubleshooting.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.mate.troubleshooting.model.TroubleshootingOpenDiscoveryAgentBindingEntity;

@Mapper
public interface TroubleshootingOpenDiscoveryAgentBindingMapper
        extends BaseMapper<TroubleshootingOpenDiscoveryAgentBindingEntity> {

    @Select("""
            SELECT workspace_id, agent_id, bound_by, bound_at, create_time, update_time
              FROM mate_troubleshooting_open_discovery_agent_binding
             WHERE workspace_id = #{workspaceId}
             LIMIT 1
            """)
    TroubleshootingOpenDiscoveryAgentBindingEntity findByWorkspace(
            @Param("workspaceId") long workspaceId);

    @Delete("""
            DELETE FROM mate_troubleshooting_open_discovery_agent_binding
             WHERE workspace_id = #{workspaceId}
            """)
    int deleteByWorkspace(@Param("workspaceId") long workspaceId);
}
