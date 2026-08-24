package vip.mate.troubleshooting.evidence;

import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        when(fixture.validation.validateCapabilities(
                7L,
                "CSDP",
                "session-svc",
                Set.of("error_log_scan", "k8s_workload_health"),
                "-15m",
                NOW))
                .thenReturn(Map.of("error_log_scan", 9L));
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
        assertThat(result.acceptance().validation().liveCapabilityDurationsMs())
                .containsOnlyKeys("error_log_scan");
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
        verify(fixture.validation).validateCapabilities(
                7L,
                "CSDP",
                "session-svc",
                Set.of("error_log_scan", "k8s_workload_health"),
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
        verify(fixture.validation, never()).validateCapabilities(
                anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void acceptsOneExactBindingWithoutWaitingForTheWorkspaceRecordingBatch() {
        Fixture fixture = fixture();
        when(fixture.recordingBatchReadiness.inspect(7L))
                .thenReturn(recordingBatch(0));
        AtomicReference<GuanceEvidenceAcceptance> saved = new AtomicReference<>();
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
                    GuanceEvidenceAcceptance acceptance = invocation.getArgument(2);
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
                "owner");

        assertThat(result.acceptedForCurrentBinding()).isTrue();
        verify(fixture.validation).validate(
                7L,
                "CSDP",
                "session-svc",
                "message_send_failed",
                "-15m",
                NOW);
        verify(fixture.recordingBatchReadiness, never()).inspect(anyLong());
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
                .hasMessageContaining("观测云只读取证尚未验收")
                .hasMessageContaining("管理员完成数据源接入");
    }

    @Test
    void anExistingAcceptanceCannotBypassTheCurrentWorkspaceBatchGate() {
        Fixture fixture = fixture();
        when(fixture.recordingBatchReadiness.inspect(7L))
                .thenReturn(recordingBatch(0));
        when(fixture.store.findByFingerprint(7L, SCOPE, FINGERPRINT))
                .thenReturn(Optional.of(acceptance(FINGERPRINT)));

        assertThatThrownBy(() -> fixture.service.requireAccepted(
                7L, "CSDP", "session-svc"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("20 executable workspace recording targets")
                .hasMessageContaining("current=0");

        verify(fixture.recordingBatchReadiness).inspect(7L);
        verify(fixture.fingerprints, never()).scopeKey(anyLong(), any(), any());
        verify(fixture.store, never()).findByFingerprint(anyLong(), any(), any());
    }

    @Test
    void genericInvestigationUsesTheExactAcceptedBindingWithoutTheBatchGate() {
        Fixture fixture = fixture();
        GuanceEvidenceAcceptance accepted = acceptance(FINGERPRINT);
        when(fixture.recordingBatchReadiness.inspect(7L))
                .thenReturn(recordingBatch(0));
        when(fixture.store.findByFingerprint(7L, SCOPE, FINGERPRINT))
                .thenReturn(Optional.of(accepted));

        assertThat(fixture.service.requireAcceptedBinding(
                7L, "CSDP", "session-svc"))
                .isEqualTo(accepted);

        verify(fixture.recordingBatchReadiness, never()).inspect(anyLong());
    }

    @Test
    void genericAuthorityFreezesOnlyCapabilitiesCoveredByTheAcceptedFingerprint() {
        Fixture fixture = fixture();
        GuanceEvidenceAcceptance accepted = acceptance(
                FINGERPRINT, Map.of("error_log_scan", 9L));
        GuanceBindingFingerprintService.Snapshot covered =
                new GuanceBindingFingerprintService.Snapshot(
                        SCOPE,
                        FINGERPRINT,
                        "CSDP",
                        "session-svc",
                        Set.of("error_log_scan", "k8s_workload_health"));
        when(fixture.fingerprints.currentForFormalAuthority(
                7L, "CSDP", "session-svc"))
                .thenReturn(Optional.of(covered));
        when(fixture.store.findByFingerprint(7L, SCOPE, FINGERPRINT))
                .thenReturn(Optional.of(accepted));

        GuanceEvidenceAcceptanceService.AcceptedBinding authority =
                fixture.service.requireAcceptedBindingAuthority(
                        7L, "CSDP", "session-svc");

        assertThat(authority.acceptance()).isEqualTo(accepted);
        assertThat(authority.readOnlySignalKinds())
                .containsExactly("error_log_scan");
    }

    @Test
    void structuralCapabilitiesWithoutTheirOwnLiveValidationDoNotBecomeGenericAuthority() {
        Fixture fixture = fixture();
        GuanceEvidenceAcceptance coreOnly = acceptance(FINGERPRINT);
        GuanceBindingFingerprintService.Snapshot covered =
                new GuanceBindingFingerprintService.Snapshot(
                        SCOPE,
                        FINGERPRINT,
                        "CSDP",
                        "session-svc",
                        Set.of("error_log_scan", "k8s_workload_health"));
        when(fixture.fingerprints.currentForFormalAuthority(
                7L, "CSDP", "session-svc"))
                .thenReturn(Optional.of(covered));
        when(fixture.store.findByFingerprint(7L, SCOPE, FINGERPRINT))
                .thenReturn(Optional.of(coreOnly));

        GuanceEvidenceAcceptanceService.AcceptedBinding authority =
                fixture.service.requireAcceptedBindingAuthority(
                        7L, "CSDP", "session-svc");

        assertThat(authority.readOnlySignalKinds()).isEmpty();
    }

    @Test
    void recordsAGenericOnlyLiveAcceptanceButKeepsTheScenarioGateClosed() {
        Fixture fixture = fixture();
        AtomicReference<GuanceEvidenceAcceptance> saved = new AtomicReference<>();
        GuanceBindingFingerprintService.Snapshot genericOnly =
                new GuanceBindingFingerprintService.Snapshot(
                        SCOPE,
                        FINGERPRINT,
                        "CSDP",
                        "session-svc",
                        Set.of("error_log_scan"));
        when(fixture.fingerprints.current(7L, "CSDP", "session-svc"))
                .thenReturn(Optional.of(genericOnly));
        when(fixture.validation.validateCapabilities(
                7L,
                "CSDP",
                "session-svc",
                Set.of("error_log_scan"),
                "-15m",
                NOW))
                .thenReturn(Map.of("error_log_scan", 7L));
        when(fixture.store.saveOrGet(eq(7L), eq(SCOPE), any()))
                .thenAnswer(invocation -> {
                    GuanceEvidenceAcceptance acceptance = invocation.getArgument(2);
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
                "unused-for-generic",
                "-15m",
                NOW,
                completeChecklist(),
                "owner");

        assertThat(result.acceptedForCurrentBinding()).isTrue();
        assertThat(result.acceptance().validation().coreChainObserved()).isFalse();
        assertThat(result.acceptance().validation().liveAcceptedSignalKinds())
                .containsExactly("error_log_scan");
        verify(fixture.validation, never()).validate(
                anyLong(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> fixture.service.requireAccepted(
                7L, "CSDP", "session-svc"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("场景排障")
                .hasMessageContaining("关联链");
    }

    private Fixture fixture() {
        GuanceBindingFingerprintService fingerprints =
                mock(GuanceBindingFingerprintService.class);
        GuanceEvidenceValidationService validation =
                mock(GuanceEvidenceValidationService.class);
        GuanceRecordingBatchReadinessService recordingBatchReadiness =
                mock(GuanceRecordingBatchReadinessService.class);
        GuanceEvidenceAcceptanceStore store =
                mock(GuanceEvidenceAcceptanceStore.class);
        when(fingerprints.scopeKey(7L, "CSDP", "session-svc"))
                .thenReturn(SCOPE);
        when(fingerprints.current(7L, "CSDP", "session-svc"))
                .thenReturn(Optional.of(snapshot()));
        when(fingerprints.currentForFormalAuthority(
                7L, "CSDP", "session-svc"))
                .thenReturn(Optional.of(snapshot()));
        when(recordingBatchReadiness.inspect(7L))
                .thenReturn(recordingBatch(20));
        return new Fixture(
                new GuanceEvidenceAcceptanceService(
                        fingerprints,
                        validation,
                        recordingBatchReadiness,
                        store,
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                fingerprints,
                validation,
                recordingBatchReadiness,
                store);
    }

    private GuanceBindingFingerprintService.Snapshot snapshot() {
        return new GuanceBindingFingerprintService.Snapshot(
                SCOPE,
                FINGERPRINT,
                "CSDP",
                "session-svc",
                Set.of(
                        "log_search",
                        "log_trace_bundle",
                        "error_log_scan",
                        "k8s_workload_health"));
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
        return acceptance(fingerprint, Map.of());
    }

    private GuanceEvidenceAcceptance acceptance(
            String fingerprint,
            Map<String, Long> liveCapabilityDurationsMs) {
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
                        40L + liveCapabilityDurationsMs.values().stream()
                                .mapToLong(Long::longValue)
                                .sum(),
                        NOW,
                        liveCapabilityDurationsMs),
                "owner",
                NOW);
    }

    private GuanceRecordingBatchReadiness recordingBatch(int count) {
        return new GuanceRecordingBatchReadiness(
                GuanceRecordingBatchReadinessService.CONTRACT_VERSION,
                "t7-first-" + "e".repeat(24),
                7L,
                GuanceRecordingTargetCatalog.CONTRACT_VERSION,
                "e".repeat(64),
                count,
                count,
                count >= GuanceRecordingTargetCatalog.MIN_WINDOW_TARGETS,
                List.of(),
                NOW.getEpochSecond(),
                count < GuanceRecordingTargetCatalog.MIN_WINDOW_TARGETS
                        ? List.of("recording target batch is incomplete")
                        : List.of());
    }

    private record Fixture(
            GuanceEvidenceAcceptanceService service,
            GuanceBindingFingerprintService fingerprints,
            GuanceEvidenceValidationService validation,
            GuanceRecordingBatchReadinessService recordingBatchReadiness,
            GuanceEvidenceAcceptanceStore store) {
    }
}
