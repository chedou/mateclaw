package vip.mate.troubleshooting.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import vip.mate.troubleshooting.model.TroubleshootingDiagnosisEntity;

import java.time.LocalDateTime;

@Mapper
public interface TroubleshootingDiagnosisMapper extends BaseMapper<TroubleshootingDiagnosisEntity> {

    /**
     * Locks one Diagnosis while a dependent immutable record is appended.
     *
     * <p>The lock is intentionally acquired only after external evidence
     * collection has completed, so a slow read-only adapter never holds the
     * case lifecycle transaction open.</p>
     */
    @Select("""
            SELECT status
              FROM mate_troubleshooting_diagnosis
             WHERE workspace_id = #{workspaceId}
               AND diagnosis_id = #{diagnosisId}
               AND deleted = 0
             FOR UPDATE
            """)
    String lockStatusForDependentAppend(
            @Param("workspaceId") long workspaceId,
            @Param("diagnosisId") String diagnosisId);

    /** Locks and returns the exact Diagnosis version for an immutable follow-up append. */
    @Select("""
            SELECT version
              FROM mate_troubleshooting_diagnosis
             WHERE workspace_id = #{workspaceId}
               AND diagnosis_id = #{diagnosisId}
               AND deleted = 0
             FOR UPDATE
            """)
    Integer lockVersionForFollowUpAppend(
            @Param("workspaceId") long workspaceId,
            @Param("diagnosisId") String diagnosisId);

    /** Schedules only channel-origin diagnoses; direct Web/API cases have no route. */
    @Update("""
            UPDATE mate_troubleshooting_diagnosis
               SET closure_notification_status = 'PENDING',
                   closure_notification_attempts = 0,
                   closure_notification_claimed_by = NULL,
                   closure_notification_lease_expires_at = NULL,
                   closure_notification_next_attempt_at = NULL,
                   closure_notification_last_error = NULL,
                   closure_notification_completed_at = NULL,
                   update_time = #{now}
             WHERE workspace_id = #{workspaceId}
               AND diagnosis_id = #{diagnosisId}
               AND source_intake_session_id IS NOT NULL
               AND status = 'CLOSED'
               AND closure_notification_status = 'NOT_APPLICABLE'
               AND deleted = 0
            """)
    int scheduleClosureNotification(
            @Param("workspaceId") long workspaceId,
            @Param("diagnosisId") String diagnosisId,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE mate_troubleshooting_diagnosis
               SET closure_notification_status = 'PROCESSING',
                   closure_notification_claimed_by = #{workerId},
                   closure_notification_lease_expires_at = #{leaseExpiresAt},
                   closure_notification_attempts = closure_notification_attempts + 1,
                   update_time = #{now}
             WHERE id = #{id}
               AND deleted = 0
               AND (
                    (closure_notification_status IN ('PENDING', 'FAILED')
                     AND (closure_notification_next_attempt_at IS NULL
                          OR closure_notification_next_attempt_at <= #{now}))
                    OR
                    (closure_notification_status = 'PROCESSING'
                     AND (closure_notification_lease_expires_at IS NULL
                          OR closure_notification_lease_expires_at < #{now}))
               )
            """)
    int claimClosureNotification(
            @Param("id") Long id,
            @Param("workerId") String workerId,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE mate_troubleshooting_diagnosis
               SET closure_notification_status = 'COMPLETED',
                   closure_notification_claimed_by = NULL,
                   closure_notification_lease_expires_at = NULL,
                   closure_notification_next_attempt_at = NULL,
                   closure_notification_last_error = NULL,
                   closure_notification_completed_at = #{now},
                   update_time = #{now}
             WHERE id = #{id}
               AND closure_notification_status = 'PROCESSING'
               AND closure_notification_claimed_by = #{workerId}
               AND deleted = 0
            """)
    int markClosureNotificationCompleted(
            @Param("id") Long id,
            @Param("workerId") String workerId,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE mate_troubleshooting_diagnosis
               SET closure_notification_status = 'FAILED',
                   closure_notification_claimed_by = NULL,
                   closure_notification_lease_expires_at = NULL,
                   closure_notification_next_attempt_at = #{nextAttemptAt},
                   closure_notification_last_error = #{error},
                   update_time = #{now}
             WHERE id = #{id}
               AND closure_notification_status = 'PROCESSING'
               AND closure_notification_claimed_by = #{workerId}
               AND deleted = 0
            """)
    int markClosureNotificationFailed(
            @Param("id") Long id,
            @Param("workerId") String workerId,
            @Param("error") String error,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("now") LocalDateTime now);
}
