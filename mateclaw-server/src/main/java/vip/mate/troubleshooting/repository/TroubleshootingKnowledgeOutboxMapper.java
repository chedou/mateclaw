package vip.mate.troubleshooting.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import vip.mate.troubleshooting.model.TroubleshootingKnowledgeOutboxEntity;

import java.time.LocalDateTime;

@Mapper
public interface TroubleshootingKnowledgeOutboxMapper
        extends BaseMapper<TroubleshootingKnowledgeOutboxEntity> {

    @Update("""
            UPDATE mate_troubleshooting_knowledge_outbox
               SET status = 'PROCESSING',
                   claimed_by = #{workerId},
                   lease_expires_at = #{leaseExpiresAt},
                   attempts = attempts + 1,
                   update_time = #{now}
             WHERE id = #{id}
               AND deleted = 0
               AND (
                    status IN ('PENDING', 'FAILED')
                    OR (status = 'PROCESSING' AND lease_expires_at < #{now})
               )
            """)
    int claim(
            @Param("id") Long id,
            @Param("workerId") String workerId,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE mate_troubleshooting_knowledge_outbox
               SET status = 'PUBLISHED',
                   published_at = #{now},
                   claimed_by = NULL,
                   lease_expires_at = NULL,
                   last_error = NULL,
                   update_time = #{now}
             WHERE id = #{id}
               AND status = 'PROCESSING'
               AND claimed_by = #{workerId}
               AND deleted = 0
            """)
    int markPublished(
            @Param("id") Long id,
            @Param("workerId") String workerId,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE mate_troubleshooting_knowledge_outbox
               SET status = 'FAILED',
                   last_error = #{error},
                   claimed_by = NULL,
                   lease_expires_at = NULL,
                   update_time = #{now}
             WHERE id = #{id}
               AND status = 'PROCESSING'
               AND claimed_by = #{workerId}
               AND deleted = 0
            """)
    int markFailed(
            @Param("id") Long id,
            @Param("workerId") String workerId,
            @Param("error") String error,
            @Param("now") LocalDateTime now);
}
