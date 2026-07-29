package vip.mate.troubleshooting.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import vip.mate.troubleshooting.model.TroubleshootingKnowledgeReviewEntity;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewSourceKey;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface TroubleshootingKnowledgeReviewMapper
        extends BaseMapper<TroubleshootingKnowledgeReviewEntity> {

    @Select("""
            SELECT id, workspace_id, review_id, origin, source_record_id,
                   selector_key, status, reviewer, reason, snapshot_json,
                   version, deleted, create_time, update_time
              FROM mate_troubleshooting_knowledge_review
             WHERE workspace_id = #{workspaceId}
               AND origin = #{origin}
               AND source_record_id = #{sourceRecordId}
               AND deleted = 0
            """)
    TroubleshootingKnowledgeReviewEntity findBySource(
            @Param("workspaceId") long workspaceId,
            @Param("origin") String origin,
            @Param("sourceRecordId") String sourceRecordId);

    @Select("""
            <script>
            SELECT id, workspace_id, review_id, origin, source_record_id,
                   selector_key, status, reviewer, reason, snapshot_json,
                   version, deleted, create_time, update_time
              FROM mate_troubleshooting_knowledge_review
             WHERE workspace_id = #{workspaceId}
               AND (
                 <foreach collection="sources" item="source" separator=" OR ">
                   (origin = #{source.origin}
                    AND source_record_id = #{source.sourceRecordId})
                 </foreach>
               )
               AND deleted = 0
             ORDER BY update_time DESC, id DESC
            </script>
            """)
    List<TroubleshootingKnowledgeReviewEntity> listBySources(
            @Param("workspaceId") long workspaceId,
            @Param("sources") List<KnowledgeReviewSourceKey> sources);

    @Update("""
            UPDATE mate_troubleshooting_knowledge_review
               SET status = #{targetStatus},
                   reviewer = #{reviewer},
                   reason = #{reason},
                   version = version + 1,
                   update_time = #{now}
             WHERE workspace_id = #{workspaceId}
               AND review_id = #{reviewId}
               AND status = #{expectedStatus}
               AND version = #{expectedVersion}
               AND deleted = 0
            """)
    int transition(
            @Param("workspaceId") long workspaceId,
            @Param("reviewId") String reviewId,
            @Param("expectedStatus") String expectedStatus,
            @Param("targetStatus") String targetStatus,
            @Param("expectedVersion") int expectedVersion,
            @Param("reviewer") String reviewer,
            @Param("reason") String reason,
            @Param("now") LocalDateTime now);
}
