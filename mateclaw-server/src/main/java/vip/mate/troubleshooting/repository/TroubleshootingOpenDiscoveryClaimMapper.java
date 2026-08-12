package vip.mate.troubleshooting.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import vip.mate.troubleshooting.model.TroubleshootingOpenDiscoveryClaimEntity;

import java.time.LocalDateTime;

@Mapper
public interface TroubleshootingOpenDiscoveryClaimMapper
        extends BaseMapper<TroubleshootingOpenDiscoveryClaimEntity> {

    @Select("""
            SELECT id, workspace_id, dedup_key, claim_token, status, diagnosis_id,
                   claimed_at, lease_expires_at, completed_at, create_time, update_time
              FROM mate_troubleshooting_open_discovery_claim
             WHERE workspace_id = #{workspaceId}
               AND dedup_key = #{dedupKey}
             LIMIT 1
            """)
    TroubleshootingOpenDiscoveryClaimEntity findByKey(
            @Param("workspaceId") long workspaceId,
            @Param("dedupKey") String dedupKey);

    @Update("""
            UPDATE mate_troubleshooting_open_discovery_claim
               SET claim_token = #{newClaimToken},
                   claimed_at = #{claimedAt},
                   lease_expires_at = #{newLeaseExpiresAt},
                   update_time = #{claimedAt}
             WHERE workspace_id = #{workspaceId}
               AND dedup_key = #{dedupKey}
               AND status = 'PROCESSING'
               AND claim_token = #{expectedClaimToken}
               AND (lease_expires_at IS NULL OR lease_expires_at <= #{claimedAt})
            """)
    int takeOver(
            @Param("workspaceId") long workspaceId,
            @Param("dedupKey") String dedupKey,
            @Param("expectedClaimToken") String expectedClaimToken,
            @Param("claimedAt") LocalDateTime claimedAt,
            @Param("newClaimToken") String newClaimToken,
            @Param("newLeaseExpiresAt") LocalDateTime newLeaseExpiresAt);

    @Update("""
            UPDATE mate_troubleshooting_open_discovery_claim
               SET status = 'COMPLETED',
                   diagnosis_id = #{diagnosisId},
                   lease_expires_at = NULL,
                   completed_at = #{completedAt},
                   update_time = #{completedAt}
             WHERE workspace_id = #{workspaceId}
               AND dedup_key = #{dedupKey}
               AND status = 'PROCESSING'
               AND claim_token = #{claimToken}
               AND lease_expires_at > #{completedAt}
            """)
    int complete(
            @Param("workspaceId") long workspaceId,
            @Param("dedupKey") String dedupKey,
            @Param("claimToken") String claimToken,
            @Param("diagnosisId") String diagnosisId,
            @Param("completedAt") LocalDateTime completedAt);

    @Delete("""
            DELETE FROM mate_troubleshooting_open_discovery_claim
             WHERE workspace_id = #{workspaceId}
               AND dedup_key = #{dedupKey}
               AND status = 'PROCESSING'
               AND claim_token = #{claimToken}
            """)
    int release(
            @Param("workspaceId") long workspaceId,
            @Param("dedupKey") String dedupKey,
            @Param("claimToken") String claimToken);
}
