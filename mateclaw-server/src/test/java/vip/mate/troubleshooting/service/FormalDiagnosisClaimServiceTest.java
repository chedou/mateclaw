package vip.mate.troubleshooting.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.annotations.Update;
import org.springframework.dao.DuplicateKeyException;
import vip.mate.troubleshooting.model.TroubleshootingFormalDiagnosisClaimEntity;
import vip.mate.troubleshooting.repository.TroubleshootingFormalDiagnosisClaimMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FormalDiagnosisClaimServiceTest {

    private static final long WORKSPACE_ID = 7L;
    private static final String DEDUP_KEY = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");

    @Mock private TroubleshootingFormalDiagnosisClaimMapper mapper;

    private FormalDiagnosisClaimService service;

    @Test
    void intakeClaimIdentityIsScopedByWorkspaceAndSession() {
        String key = FormalDiagnosisClaimKey.forIntake(7L, "intake-1");

        assertThat(key).matches("[a-f0-9]{64}");
        assertThat(key).isNotEqualTo(
                FormalDiagnosisClaimKey.forIntake(8L, "intake-1"));
        assertThat(key).isNotEqualTo(
                FormalDiagnosisClaimKey.forIntake(7L, "intake-2"));
    }

    @BeforeEach
    void setUp() {
        service = new FormalDiagnosisClaimService(mapper);
    }

    @Test
    void atomicallyAcquiresANewFormalIncidentBeforeAdmissionOrSourceIo() {
        when(mapper.findByKey(WORKSPACE_ID, DEDUP_KEY)).thenReturn(null);
        when(mapper.insert(any(TroubleshootingFormalDiagnosisClaimEntity.class)))
                .thenReturn(1);

        FormalDiagnosisClaimService.ClaimResult result = service.claim(
                WORKSPACE_ID, DEDUP_KEY, NOW, Duration.ofMinutes(5));

        assertThat(result.state())
                .isEqualTo(FormalDiagnosisClaimService.ClaimState.ACQUIRED);
        assertThat(result.claim().dedupKey()).isEqualTo(DEDUP_KEY);
        assertThat(result.claim().expiresAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void duplicateInsertObservesTheConcurrentWinnerWithoutRunningGuanceAgain() {
        TroubleshootingFormalDiagnosisClaimEntity winner = row(
                "winner", "PROCESSING", null, NOW.plusSeconds(30));
        when(mapper.findByKey(WORKSPACE_ID, DEDUP_KEY))
                .thenReturn(null)
                .thenReturn(winner);
        when(mapper.insert(any(TroubleshootingFormalDiagnosisClaimEntity.class)))
                .thenThrow(new DuplicateKeyException("winner"));

        FormalDiagnosisClaimService.ClaimResult result = service.claim(
                WORKSPACE_ID, DEDUP_KEY, NOW, Duration.ofMinutes(5));

        assertThat(result.state())
                .isEqualTo(FormalDiagnosisClaimService.ClaimState.IN_PROGRESS);
    }

    @Test
    void completedClaimReturnsTheDiagnosisIdentity() {
        when(mapper.findByKey(WORKSPACE_ID, DEDUP_KEY)).thenReturn(row(
                "winner", "COMPLETED", "diag-existing", null));

        FormalDiagnosisClaimService.ClaimResult result = service.claim(
                WORKSPACE_ID, DEDUP_KEY, NOW, Duration.ofMinutes(5));

        assertThat(result.state())
                .isEqualTo(FormalDiagnosisClaimService.ClaimState.COMPLETED);
        assertThat(result.diagnosisId()).isEqualTo("diag-existing");
    }

    @Test
    void expiredLeaseCanBeTakenOverOnlyWithCompareAndSet() {
        TroubleshootingFormalDiagnosisClaimEntity expired = row(
                "expired", "PROCESSING", null, NOW.minusSeconds(1));
        when(mapper.findByKey(WORKSPACE_ID, DEDUP_KEY)).thenReturn(expired);
        when(mapper.takeOver(
                eq(WORKSPACE_ID),
                eq(DEDUP_KEY),
                eq("expired"),
                eq(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)),
                any(),
                any()))
                .thenReturn(1);

        FormalDiagnosisClaimService.ClaimResult result = service.claim(
                WORKSPACE_ID, DEDUP_KEY, NOW, Duration.ofMinutes(5));

        assertThat(result.state())
                .isEqualTo(FormalDiagnosisClaimService.ClaimState.ACQUIRED);
        assertThat(result.claim().claimToken()).isNotEqualTo("expired");
    }

    @Test
    void completionAndReleaseRequireTheCurrentClaimToken() {
        FormalDiagnosisClaim claim = new FormalDiagnosisClaim(
                DEDUP_KEY, "claim-1", NOW, NOW.plusSeconds(300));
        LocalDateTime completedAt = LocalDateTime.ofInstant(
                NOW.plusSeconds(3), ZoneOffset.UTC);
        when(mapper.complete(
                WORKSPACE_ID, DEDUP_KEY, "claim-1", "diag-1", completedAt))
                .thenReturn(1);

        service.complete(WORKSPACE_ID, claim, "diag-1", NOW.plusSeconds(3));
        service.release(WORKSPACE_ID, claim);

        verify(mapper).complete(
                WORKSPACE_ID, DEDUP_KEY, "claim-1", "diag-1", completedAt);
        verify(mapper).release(WORKSPACE_ID, DEDUP_KEY, "claim-1");
    }

    @Test
    void commitLockAtomicallyRequiresALiveLeaseAndCurrentToken() {
        FormalDiagnosisClaim claim = new FormalDiagnosisClaim(
                DEDUP_KEY, "claim-1", NOW, NOW.plusSeconds(300));
        when(mapper.lockForCommit(WORKSPACE_ID, DEDUP_KEY, "claim-1"))
                .thenReturn(1);

        service.lockForCommit(WORKSPACE_ID, claim);

        verify(mapper).lockForCommit(WORKSPACE_ID, DEDUP_KEY, "claim-1");
    }

    @Test
    void completionChecksTheLeaseAgainstDatabaseTimeAtomically() throws Exception {
        Method complete = TroubleshootingFormalDiagnosisClaimMapper.class.getMethod(
                "complete", long.class, String.class, String.class,
                String.class, LocalDateTime.class);
        String sql = String.join(" ", complete.getAnnotation(Update.class).value());

        assertThat(sql)
                .contains("lease_expires_at > CURRENT_TIMESTAMP")
                .doesNotContain("lease_expires_at > #{completedAt}");
    }

    @Test
    void commitLockUsesDatabaseTimeAtTheTransactionsFirstStatement() throws Exception {
        Method lock = TroubleshootingFormalDiagnosisClaimMapper.class.getMethod(
                "lockForCommit", long.class, String.class, String.class);
        String sql = String.join(" ", lock.getAnnotation(Update.class).value());

        assertThat(sql).contains("lease_expires_at > CURRENT_TIMESTAMP");
    }

    @Test
    void completionAfterFourSequentialSourceTimeoutWindowsStillFitsTheLease() {
        FormalDiagnosisClaim claim = new FormalDiagnosisClaim(
                DEDUP_KEY, "claim-long", NOW, NOW.plusSeconds(300));
        Instant completed = NOW.plusSeconds(240);
        when(mapper.complete(
                WORKSPACE_ID,
                DEDUP_KEY,
                "claim-long",
                "diag-long",
                LocalDateTime.ofInstant(completed, ZoneOffset.UTC)))
                .thenReturn(1);

        service.complete(WORKSPACE_ID, claim, "diag-long", completed);

        verify(mapper).complete(
                WORKSPACE_ID,
                DEDUP_KEY,
                "claim-long",
                "diag-long",
                LocalDateTime.ofInstant(completed, ZoneOffset.UTC));
    }

    private TroubleshootingFormalDiagnosisClaimEntity row(
            String token,
            String status,
            String diagnosisId,
            Instant expiresAt) {
        TroubleshootingFormalDiagnosisClaimEntity row =
                new TroubleshootingFormalDiagnosisClaimEntity();
        row.setWorkspaceId(WORKSPACE_ID);
        row.setDedupKey(DEDUP_KEY);
        row.setClaimToken(token);
        row.setStatus(status);
        row.setDiagnosisId(diagnosisId);
        row.setLeaseExpiresAt(expiresAt == null
                ? null : LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        return row;
    }
}
