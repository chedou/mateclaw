package vip.mate.troubleshooting.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import vip.mate.troubleshooting.model.TroubleshootingDeploymentTopologyEntity;

import java.util.List;

@Mapper
public interface TroubleshootingDeploymentTopologyMapper
        extends BaseMapper<TroubleshootingDeploymentTopologyEntity> {

    @Select("""
            SELECT id
              FROM mate_workspace
             WHERE id = #{workspaceId}
               AND deleted = 0
             FOR UPDATE
            """)
    Long lockWorkspace(@Param("workspaceId") long workspaceId);

    @Select("""
            SELECT id, workspace_id, topology_id, name, system, system_label,
                   schema_version, exported_at, snapshot_json, snapshot_fingerprint,
                   node_count, link_count, configured_probe_nodes, imported_by,
                   deleted, create_time, update_time
              FROM mate_troubleshooting_deployment_topology
             WHERE workspace_id = #{workspaceId}
               AND deleted = 0
             ORDER BY create_time DESC, id DESC
             LIMIT #{limit}
            """)
    List<TroubleshootingDeploymentTopologyEntity> listByWorkspace(
            @Param("workspaceId") long workspaceId,
            @Param("limit") int limit);

    @Select("""
            SELECT id, workspace_id, topology_id, name, system, system_label,
                   schema_version, exported_at, snapshot_json, snapshot_fingerprint,
                   node_count, link_count, configured_probe_nodes, imported_by,
                   deleted, create_time, update_time
              FROM mate_troubleshooting_deployment_topology
             WHERE workspace_id = #{workspaceId}
               AND topology_id = #{topologyId}
               AND deleted = 0
            """)
    TroubleshootingDeploymentTopologyEntity findByTopologyId(
            @Param("workspaceId") long workspaceId,
            @Param("topologyId") String topologyId);

    @Select("""
            SELECT id, workspace_id, topology_id, name, system, system_label,
                   schema_version, exported_at, snapshot_json, snapshot_fingerprint,
                   node_count, link_count, configured_probe_nodes, imported_by,
                   deleted, create_time, update_time
              FROM mate_troubleshooting_deployment_topology
             WHERE workspace_id = #{workspaceId}
               AND snapshot_fingerprint = #{fingerprint}
               AND deleted = 0
            """)
    TroubleshootingDeploymentTopologyEntity findByFingerprint(
            @Param("workspaceId") long workspaceId,
            @Param("fingerprint") String fingerprint);

    @Select("""
            SELECT id, workspace_id, topology_id, name, system, system_label,
                   schema_version, exported_at, snapshot_json, snapshot_fingerprint,
                   node_count, link_count, configured_probe_nodes, imported_by,
                   deleted, create_time, update_time
              FROM mate_troubleshooting_deployment_topology
             WHERE workspace_id = #{workspaceId}
               AND name = #{name}
               AND deleted = 0
            """)
    TroubleshootingDeploymentTopologyEntity findByName(
            @Param("workspaceId") long workspaceId,
            @Param("name") String name);

    @Select("""
            SELECT COUNT(*)
              FROM mate_troubleshooting_deployment_topology
             WHERE workspace_id = #{workspaceId}
               AND deleted = 0
            """)
    long countByWorkspace(@Param("workspaceId") long workspaceId);
}
