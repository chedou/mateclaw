package vip.mate.troubleshooting.evidence;

import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuanceEvidenceAcceptanceServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-29T08:00:00Z");
    private static final String SCOPE = "a".repeat(64);
    private static final String FINGERPRINT = "b".repeat(64);

    @Test
    void rerunsTheLiveChainAndPersistsOnlyHashedStructuralProof() {
        Fixture fixture = fixture();
        AtomicReference<GuanceEvidenceAcceptance> saved =
                new AtomicReference<>();
        when(fixture.validation.validate(
                7L,
                "CSDP",
                "session-svc",
                "message_send_failed",
                "-15m",
                NOW))
                .thenReturn(report());
        when(fixture.store.saveOrGet(eq(7L), eq(SCOPE), any()))
                .thenAnswer(invocation -> {
                    GuanceEvidenceAcceptance acceptance =
                            invocation.getArgument(2);
                    saved.set(acceptance);
                    return new GuanceEvidenceAcceptanceStore.StoredAcceptance(
                            acceptance, true);
                });
        when(fixture.store.findLatest(7L, SCOPE))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(fixture.store.findByFingerprint(7L, SCOPE, FINGERPRINT))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));

        GuanceEvidenceAcceptanceView result = fixture.service.accept(
                7L,
                "CSDP",
                "session-svc",
                "message_send_failed",
                "-15m",
                NOW,
                completeChecklist(),
                "owner@example.com");

        assertThat(result.status())
                .isEqualTo(GuanceEvidenceAcceptanceView.Status.ACCEPTED);
        assertThat(result.acceptance().validation().matchCount()).isEqualTo(4);
        assertThat(result.acceptance().validation().traceEntries()).isEqualTo(3);
        assertThat(result.acceptance().validation().psIdFingerprint())
                .matches("[a-f0-9]{64}");
        assertThat(result.acceptance().toString())
                .doesNotContain(
                        "ps-message-001",
                        "message_send_failed",
                        "L::logs",
                        "runtime-secret");
        verify(fixture.validation).validate(
                7L,
                "CSDP",
                "session-svc",
                "message_send_failed",
                "-15m",
                NOW);
    }

    @Test
    void refusesIncompleteOwnerAssertionsBeforeARealSourceCall() {
        Fixture fixture = fixture();
        GuanceEvidenceAcceptance.Checklist incomplete =
                new GuanceEvidenceAcceptance.Checklist(
                        true, true, true, true, true, false, true);

        assertThatThrownBy(() -> fixture.service.accept(
                7L,
                "CSDP",
                "session-svc",
                "message_send_failed",
                "-15m",
                NOW,
                incomplete,
                "owner"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("all T7 owner confirmations");
        verify(fixture.validation, never()).validate(
                anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesOwnerAcceptanceUntilTheRecordingBatchHasTwentyExecutableTargets() {
        Fixture fixture = fixture();
        when(fixture.recordingTargetCatalog.inspect(any()))
                .thenReturn(recordingTargets(0));

        assertThatThrownBy(() -> fixture.service.accept(
                7L,
                "CSDP",
                "session-svc",
                "message_send_failed",
                "-15m",
                NOW,
                completeChecklist(),
                "owner"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("at least 20 server-frozen executable recording targets")
                .hasMessageContaining("current=0");
        verify(fixture.validation, never()).validate(
                anyLong(), any(), any(), any(), any(), any());
        verify(fixture.fingerprints, never()).current(
                anyLong(), any(), any());
    }

    @Test
    void refusesAConfigChangeAcrossTheValidationBoundary() {
        Fixture fixture = fixture();
        GuanceBindingFingerprintService.Snapshot changed =
                new GuanceBindingFingerprintService.Snapshot(
                        SCOPE, "c".repeat(64), "CSDP", "session-svc");
        when(fixture.fingerprints.current(7L, "CSDP", "session-svc"))
                .thenReturn(Optional.of(snapshot()), Optional.of(changed));
        when(fixture.validation.validate(
                7L,
                "CSDP",
                "session-svc",
                "message_send_failed",
                "-15m",
                NOW))
                .thenReturn(report());

        assertThatThrownBy(() -> fixture.service.accept(
                7L,
                "CSDP",
                "session-svc",
                "message_send_failed",
                "-15m",
                NOW,
                completeChecklist(),
                "owner"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("changed during owner acceptance");
        verify(fixture.store, never()).saveOrGet(anyLong(), any(), any());
    }

    @Test
    void marksAnOlderAcceptanceStaleAndBlocksT8Collection() {
        Fixture fixture = fixture();
        GuanceEvidenceAcceptance previous = acceptance("c".repeat(64));
        when(fixture.store.findLatest(7L, SCOPE))
                .thenReturn(Optional.of(previous));
        when(fixture.store.findByFingerprint(7L, SCOPE, FINGERPRINT))
                .thenReturn(Optional.empty());

        GuanceEvidenceAcceptanceView view =
                fixture.service.inspect(7L, "CSDP", "session-svc");

        assertThat(view.status())
                .isEqualTo(GuanceEvidenceAcceptanceView.Status.STALE);
        assertThat(view.acceptance()).isEqualTo(previous);
        assertThatThrownBy(() -> fixture.service.requireAccepted(
                7L, "CSDP", "session-svc"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("T7 owner acceptance is required");
    }

    private Fixture fixture() {
        GuanceBindingFingerprintService fingerprints =
                mock(GuanceBindingFingerprintService.class);
        GuanceEvidenceValidationService validation =
                mock(GuanceEvidenceValidationService.class);
        GuanceEvidenceReadinessService readinessService =
                mock(GuanceEvidenceReadinessService.class);
        GuanceRecordingTargetCatalog recordingTargetCatalog =
                mock(GuanceRecordingTargetCatalog.class);
        GuanceEvidenceAcceptanceStore store =
                mock(GuanceEvidenceAcceptanceStore.class);
        when(fingerprints.scopeKey(7L, "CSDP", "session-svc"))
                .thenReturn(SCOPE);
        when(fingerprints.current(7L, "CSDP", "session-svc"))
                .thenReturn(Optional.of(snapshot()));
        when(readinessService.inspect(7L, "CSDP", "session-svc"))
                .thenReturn(readiness());
        when(recordingTargetCatalog.inspect(any()))
                .thenReturn(recordingTargets(20));
        return new Fixture(
                new GuanceEvidenceAcceptanceService(
                        fingerprints,
                        readinessService,
                        validation,
                        recordingTargetCatalog,
                        store,
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                fingerprints,
                readinessService,
                validation,
                recordingTargetCatalog,
                store);
    }

    private GuanceBindingFingerprintService.Snapshot snapshot() {
        return new GuanceBindingFingerprintService.Snapshot(
                SCOPE, FINGERPRINT, "CSDP", "session-svc");
    }

    private GuanceEvidenceAcceptance.Checklist completeChecklist() {
        return new GuanceEvidenceAcceptance.Checklist(
                true, true, true, true, true, true, true);
    }

    private GuanceEvidenceValidationReport report() {
        return new GuanceEvidenceValidationReport(
                GuanceEvidenceValidationReport.Stage.CANONICAL_CHAIN_OBSERVED,
                readiness(),
                4L,
                "ps-message-001",
                3,
                40L,
                List.of(
                        new GuanceEvidenceValidationReport.Step(
                                "log_search",
                                GuanceEvidenceValidationReport.StepStatus
                                        .CANONICAL_RESULT_OBSERVED,
                                "T7-GUANCE-LOG-SEARCH",
                                "observed",
                                12L,
                                NOW),
                        new GuanceEvidenceValidationReport.Step(
                                "log_trace_bundle",
                                GuanceEvidenceValidationReport.StepStatus
                                        .CANONICAL_RESULT_OBSERVED,
                                "T7-GUANCE-TRACE-BUNDLE",
                                "observed",
                                20L,
                                NOW)),
                NOW,
                List.of());
    }

    private GuanceEvidenceReadiness readiness() {
        return new GuanceEvidenceReadiness(
                "CSDP",
                "session-svc",
                GuanceEvidenceReadiness.Status.CANONICAL_SIGNALS_OBSERVED,
                true,
                true,
                GuanceEvidenceReadiness.CredentialState.CONFIGURED,
                true,
                List.of(),
                List.of());
    }

    private GuanceEvidenceAcceptance acceptance(String fingerprint) {
        return new GuanceEvidenceAcceptance(
                "t7-012345678901234567890123",
                "CSDP",
                "session-svc",
                fingerprint,
                completeChecklist(),
                new GuanceEvidenceAcceptance.ValidationFacts(
                        4L,
                        3,
                        "d".repeat(64),
                        12L,
                        20L,
                        40L,
                        NOW),
                "owner",
                NOW);
    }

    private GuanceRecordingTargetCatalog.View recordingTargets(int count) {
        return new GuanceRecordingTargetCatalog.View(
                GuanceRecordingTargetCatalog.CONTRACT_VERSION,
                "CSDP",
                "session-svc",
                "e".repeat(64),
                count,
                count,
                List.of(),
                NOW.getEpochSecond(),
                count < GuanceRecordingTargetCatalog.MIN_WINDOW_TARGETS
                        ? List.of("recording target batch is incomplete")
                        : List.of());
    }

    private record Fixture(
            GuanceEvidenceAcceptanceService service,
            GuanceBindingFingerprintService fingerprints,
            GuanceEvidenceReadinessService readinessService,
            GuanceEvidenceValidationService validation,
            GuanceRecordingTargetCatalog recordingTargetCatalog,
            GuanceEvidenceAcceptanceStore store) {
    }
}
