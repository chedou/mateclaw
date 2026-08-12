package vip.mate.troubleshooting.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import vip.mate.troubleshooting.model.TroubleshootingOpenDiscoveryClaimEntity;
import vip.mate.troubleshooting.repository.TroubleshootingOpenDiscoveryClaimMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenDiscoveryRunClaimServiceTest {

    private static final long WORKSPACE_ID = 7L;
    private static final String DEDUP_KEY = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-12T00:00:00Z");

    @Mock private TroubleshootingOpenDiscoveryClaimMapper mapper;

    private OpenDiscoveryRunClaimService service;

    @BeforeEach
    void setUp() {
        service = new OpenDiscoveryRunClaimService(mapper);
    }

    @Test
    void atomicallyAcquiresANewIncidentKeyBeforeExternalWork() {
        when(mapper.findByKey(WORKSPACE_ID, DEDUP_KEY)).thenReturn(null);
        when(mapper.insert(any(TroubleshootingOpenDiscoveryClaimEntity.class)))
                .thenReturn(1);

        OpenDiscoveryRunClaimService.ClaimResult result = service.claim(
                WORKSPACE_ID, DEDUP_KEY, NOW, Duration.ofSeconds(80));

        assertThat(result.state())
                .isEqualTo(OpenDiscoveryRunClaimService.ClaimState.ACQUIRED);
        assertThat(result.claim().dedupKey()).isEqualTo(DEDUP_KEY);
        assertThat(result.claim().expiresAt()).isEqualTo(NOW.plusSeconds(80));
    }

    @Test
    void duplicateInsertObservesTheConcurrentWinnerInsteadOfRunningAgain() {
        TroubleshootingOpenDiscoveryClaimEntity winner = row(
                "winner", "PROCESSING", null, NOW.plusSeconds(30));
        when(mapper.findByKey(WORKSPACE_ID, DEDUP_KEY))
                .thenReturn(null)
                .thenReturn(winner);
        when(mapper.insert(any(TroubleshootingOpenDiscoveryClaimEntity.class)))
                .thenThrow(new DuplicateKeyException("winner"));

        OpenDiscoveryRunClaimService.ClaimResult result = service.claim(
                WORKSPACE_ID, DEDUP_KEY, NOW, Duration.ofSeconds(80));

        assertThat(result.state())
                .isEqualTo(OpenDiscoveryRunClaimService.ClaimState.IN_PROGRESS);
    }

    @Test
    void returnsTheCompletedDiagnosisWithoutReopeningTheRun() {
        when(mapper.findByKey(WORKSPACE_ID, DEDUP_KEY)).thenReturn(row(
                "winner", "COMPLETED", "diag-existing", null));

        OpenDiscoveryRunClaimService.ClaimResult result = service.claim(
                WORKSPACE_ID, DEDUP_KEY, NOW, Duration.ofSeconds(80));

        assertThat(result.state())
                .isEqualTo(OpenDiscoveryRunClaimService.ClaimState.COMPLETED);
        assertThat(result.diagnosisId()).isEqualTo("diag-existing");
    }

    @Test
    void takesOverOnlyAnExpiredLeaseWithCompareAndSet() {
        TroubleshootingOpenDiscoveryClaimEntity expired = row(
                "expired", "PROCESSING", null, NOW.minusSeconds(1));
        when(mapper.findByKey(WORKSPACE_ID, DEDUP_KEY))
                .thenReturn(expired);
        when(mapper.takeOver(
                eq(WORKSPACE_ID),
                eq(DEDUP_KEY),
                eq("expired"),
                eq(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)),
                any(),
                any()))
                .thenReturn(1);

        OpenDiscoveryRunClaimService.ClaimResult result = service.claim(
                WORKSPACE_ID, DEDUP_KEY, NOW, Duration.ofSeconds(80));

        assertThat(result.state())
                .isEqualTo(OpenDiscoveryRunClaimService.ClaimState.ACQUIRED);
        assertThat(result.claim().claimToken()).isNotEqualTo("expired");
    }

    @Test
    void completionRequiresTheCurrentClaimToken() {
        OpenDiscoveryRunClaim claim = new OpenDiscoveryRunClaim(
                DEDUP_KEY, "claim-1", NOW, NOW.plusSeconds(80));
        when(mapper.complete(
                WORKSPACE_ID,
                DEDUP_KEY,
                "claim-1",
                "diag-1",
                LocalDateTime.ofInstant(NOW.plusSeconds(3), ZoneOffset.UTC)))
                .thenReturn(1);

        service.complete(WORKSPACE_ID, claim, "diag-1", NOW.plusSeconds(3));

        verify(mapper).complete(
                WORKSPACE_ID,
                DEDUP_KEY,
                "claim-1",
                "diag-1",
                LocalDateTime.ofInstant(NOW.plusSeconds(3), ZoneOffset.UTC));
    }

    private TroubleshootingOpenDiscoveryClaimEntity row(
            String token,
            String status,
            String diagnosisId,
            Instant expiresAt) {
        TroubleshootingOpenDiscoveryClaimEntity row =
                new TroubleshootingOpenDiscoveryClaimEntity();
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
