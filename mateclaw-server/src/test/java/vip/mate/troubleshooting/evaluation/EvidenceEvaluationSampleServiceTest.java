package vip.mate.troubleshooting.evaluation;

import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.GuanceEvidenceReadiness;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreviewService;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.model.ClosureRecord;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceEvaluationSampleServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-29T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void capturesOneIdempotentSecretFreeGuanceSampleForTheDiagnosis() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        when(fixture.store.findBySampleKey(anyLong(), any())).thenReturn(Optional.empty());
        when(fixture.preview.preview(
                7L, "CSDP", "session-svc", "source_lookup_key", "-15m", NOW))
                .thenReturn(fullPreview());
        when(fixture.store.saveOrGet(eq(7L), any())).thenAnswer(invocation ->
                new EvidenceEvaluationSampleStore.StoredSample(
                        invocation.getArgument(1), true));

        EvidenceEvaluationSampleStore.StoredSample result = fixture.service.capture(
                7L,
                "diag-1",
                "message_send_failed",
                "source_lookup_key",
                "-15m",
                "admin@example.com");

        assertThat(result.created()).isTrue();
        assertThat(result.sample().diagnosisId()).isEqualTo("diag-1");
        assertThat(result.sample().scenarioKey()).isEqualTo("message_send_failed");
        assertThat(result.sample().sourcePlatform())
                .isEqualTo(EvidenceEvaluationSample.SourcePlatform.GUANCE);
        assertThat(result.sample().evidence().fixtureMode()).isFalse();
        assertThat(result.sample().toString())
                .doesNotContain("source_lookup_key", "runtime-secret", "L::logs");
        verify(fixture.preview).preview(
                7L, "CSDP", "session-svc", "source_lookup_key", "-15m", NOW);
    }

    @Test
    void returnsAnExistingCaptureWithoutCallingGuanceAgain() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        EvidenceEvaluationSample existing = capturedSample(false);
        when(fixture.store.findBySampleKey(anyLong(), any()))
                .thenReturn(Optional.of(existing));

        EvidenceEvaluationSampleStore.StoredSample result = fixture.service.capture(
                7L, "diag-1", "message_send_failed", "source_lookup_key", "-15m", "admin");

        assertThat(result.created()).isFalse();
        assertThat(result.sample()).isEqualTo(existing);
        verify(fixture.preview, never()).preview(
                anyLong(), any(), any(), any(), any(), any());
        verify(fixture.store, never()).saveOrGet(anyLong(), any());
    }

    @Test
    void refusesToPersistABlockedPreview() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        when(fixture.store.findBySampleKey(anyLong(), any())).thenReturn(Optional.empty());
        when(fixture.preview.preview(anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(blockedPreview());

        assertThatThrownBy(() -> fixture.service.capture(
                7L, "diag-1", "message_send_failed", "source_lookup_key", "-15m", "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("not observed");
        verify(fixture.store, never()).saveOrGet(anyLong(), any());
    }

    @Test
    void finalizesTheReferenceOnlyAfterTheLinkedDiagnosisHasAnOutcome() {
        ClosureRecord closure = new ClosureRecord(
                ClosureOutcome.RECOVERED,
                "人工扩容连接池后恢复",
                true,
                "验证写入耗时回归基线",
                null,
                "operator@example.com",
                NOW.minusSeconds(10));
        Fixture fixture = fixture(true, DiagnosisStatus.CLOSED, closure);
        EvidenceEvaluationSample captured = capturedSample(true);
        when(fixture.store.get(7L, captured.sampleId()))
                .thenReturn(Optional.of(captured));
        when(fixture.store.finalizeReference(eq(7L), any(), eq(0)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        EvidenceEvaluationSample finalized = fixture.service.finalizeReference(
                7L,
                captured.sampleId(),
                0,
                List.of("locate_failed_request", "trace_ps_id", "verify_recovery"),
                List.of("restart_production"),
                "reviewer@example.com");

        assertThat(finalized.referenceStatus())
                .isEqualTo(EvidenceEvaluationSample.ReferenceStatus.READY_FOR_EVALUATION);
        assertThat(finalized.referenceSolution().requiredStepIntents())
                .containsExactly("locate_failed_request", "trace_ps_id", "verify_recovery");
        assertThat(finalized.referenceSolution().orderingConstraints())
                .extracting(rule -> rule.beforeIntent() + "->" + rule.afterIntent())
                .containsExactly(
                        "locate_failed_request->trace_ps_id",
                        "trace_ps_id->verify_recovery");
        assertThat(finalized.outcome().outcome()).isEqualTo(ClosureOutcome.RECOVERED);
        assertThat(finalized.outcome().summary()).isEqualTo("人工扩容连接池后恢复");
    }

    @Test
    void rejectsReferenceFinalizationBeforeClosureOrWithFreeTextIntent() {
        Fixture openFixture = fixture(false, DiagnosisStatus.CONFIRMED, null);
        EvidenceEvaluationSample captured = capturedSample(false);
        when(openFixture.store.get(7L, captured.sampleId()))
                .thenReturn(Optional.of(captured));

        assertThatThrownBy(() -> openFixture.service.finalizeReference(
                7L, captured.sampleId(), 0,
                List.of("locate_failed_request"), List.of("restart_production"), "reviewer"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("closed Diagnosis");

        ClosureRecord closure = new ClosureRecord(
                ClosureOutcome.UNRESOLVED,
                "证据不足，转人工持续跟进",
                false,
                null,
                null,
                "operator",
                NOW.minusSeconds(5));
        Fixture closedFixture = fixture(false, DiagnosisStatus.CLOSED, closure);
        when(closedFixture.store.get(7L, captured.sampleId()))
                .thenReturn(Optional.of(captured));

        assertThatThrownBy(() -> closedFixture.service.finalizeReference(
                7L, captured.sampleId(), 0,
                List.of("请查看原始日志正文"), List.of("restart_production"), "reviewer"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("intent key");
        verify(closedFixture.store, never()).finalizeReference(anyLong(), any(), anyInt());
    }

    @Test
    void refusesToCopyAnUnsafeLegacyClosureSummaryIntoTheEvaluationLedger() {
        ClosureRecord unsafeClosure = new ClosureRecord(
                ClosureOutcome.UNRESOLVED,
                "L::logs:(message contains a raw developer query)",
                false,
                null,
                null,
                "operator",
                NOW.minusSeconds(5));
        Fixture fixture = fixture(false, DiagnosisStatus.CLOSED, unsafeClosure);
        EvidenceEvaluationSample captured = capturedSample(false);
        when(fixture.store.get(7L, captured.sampleId()))
                .thenReturn(Optional.of(captured));

        assertThatThrownBy(() -> fixture.service.finalizeReference(
                7L,
                captured.sampleId(),
                0,
                List.of("locate_failed_request"),
                List.of("restart_production"),
                "reviewer"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("business-safe");

        verify(fixture.store, never()).finalizeReference(anyLong(), any(), anyInt());
    }

    @Test
    void summarizesSourcesAndReadinessWithoutDeclaringT8Passed() {
        Fixture fixture = fixture(false, DiagnosisStatus.CLOSED, null);
        EvidenceEvaluationSample captured = capturedSample(false);
        EvidenceEvaluationSample ready = captured.finalizeReference(
                reference(captured.sampleId()),
                new EvidenceEvaluationSample.OutcomeSnapshot(
                        ClosureOutcome.UNRESOLVED,
                        "仍需人工定位",
                        false,
                        NOW),
                "reviewer",
                NOW);
        when(fixture.store.list(7L, null, 100)).thenReturn(List.of(ready, captured));

        EvidenceEvaluationSampleLedger ledger = fixture.service.list(7L, null, 100);

        assertThat(ledger.summary().total()).isEqualTo(2);
        assertThat(ledger.summary().guance()).isEqualTo(2);
        assertThat(ledger.summary().recordedReplay()).isZero();
        assertThat(ledger.summary().readyForEvaluation()).isEqualTo(1);
        assertThat(ledger.summary().minimumEvaluationTarget()).isEqualTo(20);
        assertThat(ledger.toString()).doesNotContain("passed", "T8_PASSED");
    }

    private Fixture fixture(
            boolean diagnosisFixtureMode,
            DiagnosisStatus status,
            ClosureRecord closure) {
        GuanceEvidenceSpinePreviewService preview =
                mock(GuanceEvidenceSpinePreviewService.class);
        TroubleshootingPersistenceService persistence =
                mock(TroubleshootingPersistenceService.class);
        EvidenceEvaluationSampleStore store = mock(EvidenceEvaluationSampleStore.class);
        Diagnosis diagnosis = mock(Diagnosis.class);
        IncidentContext incident = mock(IncidentContext.class);
        when(incident.system()).thenReturn("CSDP");
        when(incident.service()).thenReturn("session-svc");
        when(incident.occurredAt()).thenReturn(NOW);
        when(diagnosis.diagnosisId()).thenReturn("diag-1");
        when(diagnosis.incident()).thenReturn(incident);
        when(diagnosis.fixtureMode()).thenReturn(diagnosisFixtureMode);
        when(diagnosis.status()).thenReturn(status);
        when(diagnosis.closure()).thenReturn(closure);
        when(persistence.get(7L, "diag-1"))
                .thenReturn(new StoredDiagnosis(diagnosis, 3, false));
        return new Fixture(
                new EvidenceEvaluationSampleService(
                        preview, persistence, store, CLOCK),
                preview,
                persistence,
                store);
    }

    private EvidenceEvaluationSample capturedSample(boolean diagnosisFixtureMode) {
        return EvidenceEvaluationSample.captured(
                "eval-012345678901234567890123",
                "a".repeat(64),
                "diag-1",
                "CSDP",
                "session-svc",
                "message_send_failed",
                fullPreview(),
                diagnosisFixtureMode,
                "admin",
                NOW);
    }

    private vip.mate.troubleshooting.synthesis.ReferenceSolution reference(String sampleId) {
        return new vip.mate.troubleshooting.synthesis.ReferenceSolution(
                sampleId + "/reference/v1",
                "message_send_failed",
                List.of("locate_failed_request", "trace_ps_id"),
                List.of("restart_production"),
                List.of(new vip.mate.troubleshooting.synthesis.ReferenceSolution.OrderingConstraint(
                        "locate_failed_request", "trace_ps_id")),
                List.of("log_search", "log_trace_bundle", "contrast_sample"));
    }

    private GuanceEvidenceSpinePreview fullPreview() {
        return new GuanceEvidenceSpinePreview(
                GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED,
                readiness(), 4L, "ps-message-001", 3,
                List.of("gateway", "session-svc", "openim"), 2, 42L,
                new GuanceEvidenceSpinePreview.Contrast(
                        true, 100, 92, 100, 3, 0.92, 0.03, 0.89),
                3, 50L,
                List.of(
                        observed("log_search", "T8-GUANCE-LOG-SEARCH"),
                        observed("log_trace_bundle", "T8-GUANCE-TRACE-BUNDLE"),
                        observed("contrast_sample", "T8-GUANCE-CONTRAST-SAMPLE")),
                NOW, List.of("待 T7/T8 验收"));
    }

    private GuanceEvidenceSpinePreview blockedPreview() {
        return new GuanceEvidenceSpinePreview(
                GuanceEvidenceSpinePreview.Stage.BLOCKED,
                readiness(), null, null, null, List.of(), 0, null,
                GuanceEvidenceSpinePreview.Contrast.unavailable(),
                0, 5L,
                List.of(
                        notRun("log_search", "T8-GUANCE-LOG-SEARCH"),
                        notRun("log_trace_bundle", "T8-GUANCE-TRACE-BUNDLE"),
                        notRun("contrast_sample", "T8-GUANCE-CONTRAST-SAMPLE")),
                NOW, List.of("blocked"));
    }

    private GuanceEvidenceReadiness readiness() {
        return new GuanceEvidenceReadiness(
                "CSDP", "session-svc",
                GuanceEvidenceReadiness.Status.READY_FOR_VALIDATION,
                true, true,
                GuanceEvidenceReadiness.CredentialState.CONFIGURED,
                true, List.of(), List.of());
    }

    private GuanceEvidenceSpinePreview.Step observed(String kind, String ref) {
        return new GuanceEvidenceSpinePreview.Step(
                kind, GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                ref, NOW);
    }

    private GuanceEvidenceSpinePreview.Step notRun(String kind, String ref) {
        return new GuanceEvidenceSpinePreview.Step(
                kind, GuanceEvidenceSpinePreview.StepStatus.NOT_RUN, ref, null);
    }

    private record Fixture(
            EvidenceEvaluationSampleService service,
            GuanceEvidenceSpinePreviewService preview,
            TroubleshootingPersistenceService persistence,
            EvidenceEvaluationSampleStore store) {
    }
}
