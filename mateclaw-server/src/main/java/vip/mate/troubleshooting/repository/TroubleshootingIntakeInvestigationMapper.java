package vip.mate.troubleshooting.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import vip.mate.troubleshooting.intake.TroubleshootingIntakeInvestigationEntity;

import java.time.LocalDateTime;

/** Atomic lease transitions for durable intake investigation work. */
@Mapper
public interface TroubleshootingIntakeInvestigationMapper
        extends BaseMapper<TroubleshootingIntakeInvestigationEntity> {

    @Update("""
            UPDATE mate_troubleshooting_intake_investigation
               SET status = 'PROCESSING',
                   claimed_by = #{workerId},
                   lease_expires_at = #{leaseExpiresAt},
                   attempts = attempts + 1,
                   update_time = #{now}
             WHERE id = #{id}
               AND deleted = 0
               AND attempts < #{maxAttempts}
               AND (
                    (status IN ('PENDING', 'FAILED')
                        AND (next_attempt_at IS NULL OR next_attempt_at <= #{now}))
                    OR (status = 'PROCESSING'
                        AND (lease_expires_at IS NULL OR lease_expires_at < #{now}))
               )
            """)
    int claim(
            @Param("id") Long id,
            @Param("workerId") String workerId,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
            @Param("now") LocalDateTime now,
            @Param("maxAttempts") int maxAttempts);

    @Update("""
            UPDATE mate_troubleshooting_intake_investigation
               SET diagnosis_id = #{diagnosisId},
                   update_time = #{now}
             WHERE id = #{id}
               AND status IN ('PROCESSING', 'TERMINAL_PROCESSING')
               AND claimed_by = #{workerId}
               AND diagnosis_id IS NULL
               AND deleted = 0
            """)
    int attachDiagnosis(
            @Param("id") Long id,
            @Param("workerId") String workerId,
            @Param("diagnosisId") String diagnosisId,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE mate_troubleshooting_intake_investigation
               SET status = 'COMPLETED',
                   completed_at = #{now},
                   claimed_by = NULL,
                   lease_expires_at = NULL,
                   next_attempt_at = NULL,
                   last_error = NULL,
                   update_time = #{now}
             WHERE id = #{id}
               AND status = 'PROCESSING'
               AND claimed_by = #{workerId}
               AND diagnosis_id IS NOT NULL
               AND deleted = 0
            """)
    int markCompleted(
            @Param("id") Long id,
            @Param("workerId") String workerId,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE mate_troubleshooting_intake_investigation
               SET status = 'FAILED',
                   last_error = #{error},
                   claimed_by = NULL,
                   lease_expires_at = NULL,
                   next_attempt_at = #{nextAttemptAt},
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
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE mate_troubleshooting_intake_investigation
               SET status = 'TERMINAL_PENDING',
                   last_error = #{error},
                   claimed_by = NULL,
                   lease_expires_at = NULL,
                   next_attempt_at = #{nextAttemptAt},
                   update_time = #{now}
             WHERE id = #{id}
               AND status = 'PROCESSING'
               AND claimed_by = #{workerId}
               AND deleted = 0
            """)
    int markTerminalPending(
            @Param("id") Long id,
            @Param("workerId") String workerId,
            @Param("error") String error,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE mate_troubleshooting_intake_investigation
               SET status = 'TERMINAL_PROCESSING',
                   claimed_by = #{workerId},
                   lease_expires_at = #{leaseExpiresAt},
                   terminal_attempts = terminal_attempts + 1,
                   update_time = #{now}
             WHERE id = #{id}
               AND deleted = 0
               AND (
                    (status = 'TERMINAL_PENDING'
                        AND (next_attempt_at IS NULL OR next_attempt_at <= #{now}))
                    OR (status = 'TERMINAL_PROCESSING'
                        AND (lease_expires_at IS NULL OR lease_expires_at < #{now}))
                    OR (status = 'PROCESSING'
                        AND attempts >= #{maxAttempts}
                        AND (lease_expires_at IS NULL OR lease_expires_at < #{now}))
               )
            """)
    int claimTerminal(
            @Param("id") Long id,
            @Param("workerId") String workerId,
            @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt,
            @Param("now") LocalDateTime now,
            @Param("maxAttempts") int maxAttempts);

    @Update("""
            UPDATE mate_troubleshooting_intake_investigation
               SET status = 'COMPLETED',
                   completed_at = #{now},
                   claimed_by = NULL,
                   lease_expires_at = NULL,
                   next_attempt_at = NULL,
                   last_error = NULL,
                   update_time = #{now}
             WHERE id = #{id}
               AND status = 'TERMINAL_PROCESSING'
               AND claimed_by = #{workerId}
               AND deleted = 0
            """)
    int markTerminalCompleted(
            @Param("id") Long id,
            @Param("workerId") String workerId,
            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE mate_troubleshooting_intake_investigation
               SET status = 'TERMINAL_PENDING',
                   last_error = #{error},
                   claimed_by = NULL,
                   lease_expires_at = NULL,
                   next_attempt_at = #{nextAttemptAt},
                   update_time = #{now}
             WHERE id = #{id}
               AND status = 'TERMINAL_PROCESSING'
               AND claimed_by = #{workerId}
               AND deleted = 0
            """)
    int rescheduleTerminal(
            @Param("id") Long id,
            @Param("workerId") String workerId,
            @Param("error") String error,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
            @Param("now") LocalDateTime now);
}
