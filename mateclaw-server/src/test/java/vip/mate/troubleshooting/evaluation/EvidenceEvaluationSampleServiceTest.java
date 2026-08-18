package vip.mate.troubleshooting.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.EvidenceSpineTimings;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptance;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptanceService;
import vip.mate.troubleshooting.evidence.GuanceEvidenceReadiness;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpineObservation;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreviewService;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.model.ClosureRecord;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.pilot.TroubleshootingPilotPlanService;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.service.TroubleshootingPlaybookVersionService;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;
import vip.mate.troubleshooting.synthesis.LogTraceSkeleton;
import vip.mate.troubleshooting.synthesis.SopSynthesisPreview;
import vip.mate.troubleshooting.synthesis.SopSynthesisRequest;
import vip.mate.troubleshooting.synthesis.SopSynthesisService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class EvidenceEvaluationSampleServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-29T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void capturesOneIdempotentSecretFreeGuanceSampleForTheDiagnosis() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        when(fixture.store.findBySampleKey(anyLong(), any())).thenReturn(Optional.empty());
        when(fixture.preview.observe(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", NOW))
                .thenReturn(observation(fullPreview()));
        when(fixture.store.saveOrGet(eq(7L), any())).thenAnswer(invocation ->
                new EvidenceEvaluationSampleStore.StoredSample(
                        invocation.getArgument(1), true));

        EvidenceEvaluationSampleStore.StoredSample result = fixture.service.capture(
                7L,
                "diag-1",
                "admin@example.com");

        assertThat(result.created()).isTrue();
        assertThat(result.sample().diagnosisId()).isEqualTo("diag-1");
        assertThat(result.sample().scenarioKey()).isEqualTo("message_send_failed");
        assertThat(result.sample().sourcePlatform())
                .isEqualTo(EvidenceEvaluationSample.SourcePlatform.GUANCE);
        assertThat(result.sample().evidence().fixtureMode()).isFalse();
        assertThat(result.sample().diagnosisRehearsal()).isFalse();
        assertThat(result.sample().pilotPlanVersion()).isEqualTo(2);
        assertThat(result.sample().sourcePlaybookVersionRef())
                .isEqualTo(new PlaybookVersionRef("playbook-message-send", 3));
        assertThat(result.sample().modelInputHash()).matches("[a-f0-9]{64}");
        assertThat(result.sample().toString())
                .doesNotContain("source_lookup_key", "runtime-secret", "L::logs");
        verify(fixture.preview).observe(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", NOW);
        verify(fixture.acceptance, times(2)).requireAccepted(
                7L, "CSDP", "session-svc");
    }

    @Test
    void blocksGuanceCaptureBeforeAnySourceCallWhenCurrentT7BindingIsNotAccepted() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        doThrow(new MateClawException(
                "err.troubleshooting.guance_acceptance_conflict",
                409,
                "T7 owner acceptance is required"))
                .when(fixture.acceptance)
                .requireAccepted(7L, "CSDP", "session-svc");

        assertThatThrownBy(() -> fixture.service.capture(
                7L,
                "diag-1",
                "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("T7 owner acceptance is required");

        verify(fixture.preview, never()).observe(
                anyLong(), any(), any(), any(), any(), any());
        verify(fixture.store, never()).saveOrGet(anyLong(), any());
    }

    @Test
    void rejectsRehearsalBeforeAcceptanceOrAnyGuanceCall() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        when(fixture.diagnosis.rehearsal()).thenReturn(true);

        assertThatThrownBy(() -> fixture.service.capture(7L, "diag-1", "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("rehearsal Diagnosis");

        verify(fixture.acceptance, never()).requireAccepted(anyLong(), any(), any());
        verify(fixture.preview, never()).observe(
                anyLong(), any(), any(), any(), any(), any());
        verify(fixture.store, never()).saveOrGet(anyLong(), any());
    }

    @Test
    void rejectsDiagnosisOutsideTheProductionPilotBeforeAnyGuanceCall() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        when(fixture.persistence.get(7L, "diag-1"))
                .thenReturn(new StoredDiagnosis(fixture.diagnosis, 3, false, null));

        assertThatThrownBy(() -> fixture.service.capture(7L, "diag-1", "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("not enrolled in the production pilot");

        verify(fixture.acceptance, never()).requireAccepted(anyLong(), any(), any());
        verify(fixture.preview, never()).observe(
                anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsScenarioScopedFormalSampleBeforeAcceptanceOrGuance() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        PlaybookVersionRef ref = new PlaybookVersionRef("playbook-message-send", 3);
        when(fixture.diagnosis.sopKey())
                .thenReturn("csdp:scenario:message_send_failed");
        ApprovedPlaybookVersion approved = mock(ApprovedPlaybookVersion.class);
        when(approved.selectorKey())
                .thenReturn("csdp:scenario:message_send_failed");
        when(approved.playbook()).thenReturn(
                formalPlaybook("scenario:message_send_failed", "source_lookup_key"));
        when(fixture.playbookVersions.findByRef(7L, ref))
                .thenReturn(Optional.of(approved));

        assertThatThrownBy(() -> fixture.service.capture(7L, "diag-1", "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("D20 scenario-scoped binding");

        verify(fixture.acceptance, never()).requireAccepted(anyLong(), any(), any());
        verify(fixture.preview, never()).observe(
                anyLong(), any(), any(), any(), any(), any());
        verify(fixture.store, never()).saveOrGet(anyLong(), any());
    }

    @Test
    void rejectsAFrozenPlaybookThatIsNoLongerTheActiveAuthority() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        when(fixture.playbookVersions.activeRef(
                7L, "csdp:701018"))
                .thenReturn(Optional.of(new PlaybookVersionRef(
                        "playbook-message-send", 4)));

        assertThatThrownBy(() -> fixture.service.capture(7L, "diag-1", "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("no longer the active authority");

        verify(fixture.acceptance, never()).requireAccepted(anyLong(), any(), any());
        verify(fixture.preview, never()).observe(
                anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void pilotRevisionChangedAfterGuanceObservationCannotBePersisted() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        when(fixture.pilotPlans.enrollmentVersion(
                7L, "CSDP", "session-svc", false)).thenReturn(2, 3);
        when(fixture.preview.observe(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", NOW))
                .thenReturn(observation(fullPreview()));

        assertThatThrownBy(() -> fixture.service.capture(7L, "diag-1", "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("pilot plan changed");

        verify(fixture.preview).observe(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", NOW);
        verify(fixture.store, never()).saveOrGet(anyLong(), any());
    }

    @Test
    void activePlaybookChangedAfterGuanceObservationCannotBePersisted() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        PlaybookVersionRef frozen = new PlaybookVersionRef("playbook-message-send", 3);
        when(fixture.playbookVersions.activeRef(
                7L, "csdp:701018"))
                .thenReturn(
                        Optional.of(frozen),
                        Optional.of(new PlaybookVersionRef("playbook-message-send", 4)));
        when(fixture.preview.observe(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", NOW))
                .thenReturn(observation(fullPreview()));

        assertThatThrownBy(() -> fixture.service.capture(7L, "diag-1", "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("Playbook changed");

        verify(fixture.store, never()).saveOrGet(anyLong(), any());
    }

    @Test
    void ownerAcceptanceChangedAfterGuanceObservationCannotBePersisted() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        when(fixture.acceptance.requireAccepted(7L, "CSDP", "session-svc"))
                .thenReturn(
                        accepted("t7-012345678901234567890123", "a".repeat(64)),
                        accepted("t7-abcdefghijklmnopqrstuvwx", "b".repeat(64)));
        when(fixture.preview.observe(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", NOW))
                .thenReturn(observation(fullPreview()));

        assertThatThrownBy(() -> fixture.service.capture(7L, "diag-1", "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("acceptance changed");

        verify(fixture.store, never()).saveOrGet(anyLong(), any());
    }

    @Test
    void refusesToPersistCoreOnlyGuanceEvidenceAsAFormalSample() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        when(fixture.preview.observe(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", NOW))
                .thenReturn(observation(corePreview()));

        assertThatThrownBy(() -> fixture.service.capture(7L, "diag-1", "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("full Guance Evidence Spine");

        verify(fixture.store, never()).saveOrGet(anyLong(), any());
    }

    @Test
    void derivesAnErrorCodePlaybookScenarioFromItsFrozenEvidenceTarget() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        PlaybookVersionRef ref = new PlaybookVersionRef("playbook-message-send", 3);
        when(fixture.diagnosis.sopKey()).thenReturn("csdp:904003");
        ApprovedPlaybookVersion approved = mock(ApprovedPlaybookVersion.class);
        when(approved.selectorKey()).thenReturn("csdp:904003");
        when(approved.playbook()).thenReturn(formalPlaybook("904003", "itgw_access_failed"));
        when(fixture.playbookVersions.findByRef(7L, ref)).thenReturn(Optional.of(approved));
        when(fixture.playbookVersions.activeRef(7L, "csdp:904003"))
                .thenReturn(Optional.of(ref));
        when(fixture.preview.observe(
                7L, "CSDP", "session-svc", "itgw_access_failed", "-15m", NOW))
                .thenReturn(observation(fullPreview()));
        when(fixture.store.saveOrGet(eq(7L), any())).thenAnswer(invocation ->
                new EvidenceEvaluationSampleStore.StoredSample(
                        invocation.getArgument(1), true));

        EvidenceEvaluationSample sample = fixture.service.capture(
                7L, "diag-1", "admin").sample();

        assertThat(sample.scenarioKey()).isEqualTo("itgw_access_failed");
        verify(fixture.preview).observe(
                7L, "CSDP", "session-svc", "itgw_access_failed", "-15m", NOW);
    }

    @Test
    void rerunsGuanceAndReusesTheLatestRevisionWhenTheFrozenInputIsUnchanged() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        GuanceEvidenceSpineObservation observation = observation(fullPreview());
        String fingerprint = new EvaluationModelInputFactory(
                new ObjectMapper().findAndRegisterModules())
                .create("CSDP", "session-svc", "message_send_failed", observation)
                .fingerprint();
        EvidenceEvaluationSample existing = EvidenceEvaluationSample.capturedFormal(
                "eval-012345678901234567890123",
                "a".repeat(64),
                "a".repeat(64),
                1,
                "diag-1",
                "CSDP",
                "session-svc",
                "message_send_failed",
                fullPreview(),
                fingerprint,
                NOW,
                false,
                2,
                new PlaybookVersionRef("playbook-message-send", 3),
                "admin",
                NOW);
        when(fixture.store.findLatestByCaptureIdentity(anyLong(), any()))
                .thenReturn(Optional.of(existing));
        when(fixture.preview.observe(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", NOW))
                .thenReturn(observation);

        EvidenceEvaluationSampleStore.StoredSample result = fixture.service.capture(
                7L, "diag-1", "admin");

        assertThat(result.created()).isFalse();
        assertThat(result.sample()).isEqualTo(existing);
        verify(fixture.preview).observe(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", NOW);
        verify(fixture.store, never()).saveOrGet(anyLong(), any());
    }

    @Test
    void evidenceDriftCreatesANewImmutableRevisionInsteadOfReturningTheOldSample() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        String captureIdentity = EvaluationKeys.captureIdentityKey(
                7L,
                "diag-1",
                "message_send_failed",
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                "message_send_failed",
                "-15m",
                NOW);
        EvidenceEvaluationSample old = EvidenceEvaluationSample.captured(
                "eval-old-0123456789012345678901",
                captureIdentity,
                captureIdentity,
                1,
                "diag-1",
                "CSDP",
                "session-svc",
                "message_send_failed",
                fullPreview(),
                "c".repeat(64),
                NOW,
                false,
                "admin",
                NOW.minusSeconds(60));
        when(fixture.store.findLatestByCaptureIdentity(anyLong(), any()))
                .thenReturn(Optional.of(old));
        when(fixture.preview.observe(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", NOW))
                .thenReturn(observation(fullPreview()));
        when(fixture.store.saveOrGet(eq(7L), any())).thenAnswer(invocation ->
                new EvidenceEvaluationSampleStore.StoredSample(
                        invocation.getArgument(1), true));

        EvidenceEvaluationSample recaptured = fixture.service.capture(
                7L, "diag-1", "admin")
                .sample();

        assertThat(recaptured.captureRevision()).isEqualTo(2);
        assertThat(recaptured.captureIdentityKey()).isEqualTo(old.captureIdentityKey());
        assertThat(recaptured.sampleKey()).isNotEqualTo(old.sampleKey());
        assertThat(old.captureRevision()).isEqualTo(1);
    }

    @Test
    void aConcurrentDifferentFingerprintWinnerRetriesWithTheNextRevision() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        String captureIdentity = EvaluationKeys.captureIdentityKey(
                7L,
                "diag-1",
                "message_send_failed",
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                "message_send_failed",
                "-15m",
                NOW);
        EvidenceEvaluationSample old = EvidenceEvaluationSample.captured(
                "eval-old-0123456789012345678901",
                captureIdentity,
                captureIdentity,
                1,
                "diag-1",
                "CSDP",
                "session-svc",
                "message_send_failed",
                fullPreview(),
                "c".repeat(64),
                NOW,
                false,
                "admin",
                NOW.minusSeconds(60));
        String winnerKey = EvaluationKeys.sampleKey(
                7L,
                "diag-1",
                "message_send_failed",
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                "message_send_failed",
                "-15m",
                NOW,
                2);
        EvidenceEvaluationSample concurrentWinner = EvidenceEvaluationSample.captured(
                "eval-winner-0123456789012345678",
                winnerKey,
                captureIdentity,
                2,
                "diag-1",
                "CSDP",
                "session-svc",
                "message_send_failed",
                fullPreview(),
                "d".repeat(64),
                NOW,
                false,
                "another-worker",
                NOW);
        when(fixture.store.findLatestByCaptureIdentity(7L, captureIdentity))
                .thenReturn(Optional.of(old), Optional.of(concurrentWinner));
        when(fixture.preview.observe(
                7L, "CSDP", "session-svc", "message_send_failed", "-15m", NOW))
                .thenReturn(observation(fullPreview()));
        when(fixture.store.saveOrGet(eq(7L), any()))
                .thenReturn(new EvidenceEvaluationSampleStore.StoredSample(
                        concurrentWinner, false))
                .thenAnswer(invocation -> new EvidenceEvaluationSampleStore.StoredSample(
                        invocation.getArgument(1), true));

        EvidenceEvaluationSampleStore.StoredSample stored = fixture.service.capture(
                7L, "diag-1", "admin");

        assertThat(stored.created()).isTrue();
        assertThat(stored.sample().captureRevision()).isEqualTo(3);
        assertThat(stored.sample().captureIdentityKey()).isEqualTo(captureIdentity);
        assertThat(stored.sample().modelInputHash())
                .isNotEqualTo(concurrentWinner.modelInputHash());
        verify(fixture.store, org.mockito.Mockito.times(2)).saveOrGet(eq(7L), any());
    }

    @Test
    void capturesRecordedReplayAsASeparateFixtureEvidenceSource() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        when(fixture.store.findBySampleKey(anyLong(), any())).thenReturn(Optional.empty());
        when(fixture.replay.preview(eq(7L), any(SopSynthesisRequest.class)))
                .thenReturn(replayPreview());
        when(fixture.store.saveOrGet(eq(7L), any())).thenAnswer(invocation ->
                new EvidenceEvaluationSampleStore.StoredSample(
                        invocation.getArgument(1), true));

        EvidenceEvaluationSample sample = fixture.service.captureRecordedReplay(
                7L, "diag-1", "admin").sample();

        assertThat(sample.sourcePlatform())
                .isEqualTo(EvidenceEvaluationSample.SourcePlatform.RECORDED_REPLAY);
        assertThat(sample.evidence().fixtureMode()).isTrue();
        assertThat(sample.evidence().sourceRequestCount()).isEqualTo(3);
        assertThat(sample.modelInputHash()).matches("[a-f0-9]{64}");
        assertThat(sample.toString())
                .doesNotContain("source_lookup_key", "recorded-replay:message-send-failed");
        verify(fixture.replay).preview(eq(7L), any(SopSynthesisRequest.class));
        verify(fixture.preview, never()).observe(
                anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void refusesReplayCaptureWhenTheServerOwnedCatalogTargetIsNotReady() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        when(fixture.replayCapability.inspect(7L, "diag-1"))
                .thenReturn(new RecordedReplayEvaluationCapability(
                        false,
                        "FIXTURE_NOT_FOUND",
                        "fixture missing",
                        null,
                        null,
                        null));

        assertThatThrownBy(() -> fixture.service.captureRecordedReplay(
                7L, "diag-1", "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("server-owned Replay target");

        verify(fixture.replay, never()).preview(anyLong(), any());
        verify(fixture.store, never()).saveOrGet(anyLong(), any());
    }

    @Test
    void refusesToPersistABlockedPreview() {
        Fixture fixture = fixture(false, DiagnosisStatus.READY_FOR_HUMAN, null);
        when(fixture.store.findBySampleKey(anyLong(), any())).thenReturn(Optional.empty());
        when(fixture.preview.observe(anyLong(), any(), any(), any(), any(), any()))
                .thenReturn(new GuanceEvidenceSpineObservation(blockedPreview(), null));

        assertThatThrownBy(() -> fixture.service.capture(
                7L, "diag-1", "admin"))
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
        Fixture fixture = fixture(false, DiagnosisStatus.CLOSED, closure);
        EvidenceEvaluationSample captured = capturedSample(false);
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
                EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
                new EvidenceEvaluationSample.HumanBaseline(
                        45,
                        EvidenceEvaluationSample.HumanBaseline.Basis.MEASURED,
                        "工单时间戳"),
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
        assertThat(finalized.humanBaseline().minutesToLocate()).isEqualTo(45L);
    }

    @Test
    void humanTimeBaselineIsRejectedForFixtureAndRecordedReplaySamples() {
        ClosureRecord closure = new ClosureRecord(
                ClosureOutcome.RECOVERED,
                "人工确认恢复",
                true,
                "业务验证通过",
                null,
                "operator@example.com",
                NOW.minusSeconds(10));
        EvidenceEvaluationSample.HumanBaseline baseline =
                new EvidenceEvaluationSample.HumanBaseline(
                        45,
                        EvidenceEvaluationSample.HumanBaseline.Basis.MEASURED,
                        "工单时间戳");

        Fixture fixtureDiagnosis = fixture(true, DiagnosisStatus.CLOSED, closure);
        EvidenceEvaluationSample fixtureSample = capturedSample(true);
        when(fixtureDiagnosis.store.get(7L, fixtureSample.sampleId()))
                .thenReturn(Optional.of(fixtureSample));
        assertThatThrownBy(() -> fixtureDiagnosis.service.finalizeReference(
                7L,
                fixtureSample.sampleId(),
                0,
                List.of("locate_failed_request"),
                List.of("restart_production"),
                EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
                baseline,
                "reviewer"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("only valid for a real Guance Diagnosis");
        verify(fixtureDiagnosis.store, never())
                .finalizeReference(anyLong(), any(), anyInt());

        Fixture replayDiagnosis = fixture(false, DiagnosisStatus.CLOSED, closure);
        EvidenceEvaluationSample replaySample = EvidenceEvaluationSample.capturedReplay(
                "eval-replay-baseline",
                "b".repeat(64),
                "diag-1",
                "CSDP",
                "session-svc",
                "message_send_failed",
                replayPreview(),
                "c".repeat(64),
                NOW,
                false,
                "admin",
                NOW);
        when(replayDiagnosis.store.get(7L, replaySample.sampleId()))
                .thenReturn(Optional.of(replaySample));
        assertThatThrownBy(() -> replayDiagnosis.service.finalizeReference(
                7L,
                replaySample.sampleId(),
                0,
                List.of("locate_failed_request"),
                List.of("restart_production"),
                EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
                baseline,
                "reviewer"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("only valid for a real Guance Diagnosis");
        verify(replayDiagnosis.store, never())
                .finalizeReference(anyLong(), any(), anyInt());
    }

    @Test
    void aCoreOnlySampleDoesNotInventContrastAsARequiredReferenceKind() {
        ClosureRecord closure = new ClosureRecord(
                ClosureOutcome.UNRESOLVED,
                "核心调用链已确认，对照样本缺失",
                false,
                null,
                null,
                "operator@example.com",
                NOW.minusSeconds(10));
        Fixture fixture = fixture(false, DiagnosisStatus.CLOSED, closure);
        EvidenceEvaluationSample captured = EvidenceEvaluationSample.captured(
                "eval-core-01234567890123456789",
                "d".repeat(64),
                "diag-1",
                "CSDP",
                "session-svc",
                "message_send_failed",
                corePreview(),
                false,
                "admin",
                NOW);
        when(fixture.store.get(7L, captured.sampleId()))
                .thenReturn(Optional.of(captured));
        when(fixture.store.finalizeReference(eq(7L), any(), eq(0)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        EvidenceEvaluationSample finalized = fixture.service.finalizeReference(
                7L,
                captured.sampleId(),
                0,
                List.of("locate_failed_request", "trace_ps_id"),
                List.of("restart_production"),
                "reviewer");

        assertThat(finalized.referenceSolution().requiredEvidenceKinds())
                .containsExactly("log_search", "log_trace_bundle");
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

    @Test
    void northStarUsesOnlyMatchingRealGuanceSamplesAndRuns() {
        Fixture fixture = fixture(false, DiagnosisStatus.CLOSED, null);
        EvidenceEvaluationSample.HumanBaseline measured =
                new EvidenceEvaluationSample.HumanBaseline(
                        40,
                        EvidenceEvaluationSample.HumanBaseline.Basis.MEASURED,
                        "工单时间戳");
        EvidenceEvaluationSample.OutcomeSnapshot outcome =
                new EvidenceEvaluationSample.OutcomeSnapshot(
                        ClosureOutcome.RECOVERED,
                        "人工确认恢复",
                        true,
                        NOW);
        EvidenceEvaluationSample real = capturedGuance(
                "eval-real", "d".repeat(64), false)
                .finalizeReference(
                        reference("eval-real"),
                        EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
                        measured,
                        outcome,
                        "reviewer",
                        NOW);
        EvidenceEvaluationSample fixtureSample = capturedGuance(
                "eval-fixture", "e".repeat(64), true)
                .finalizeReference(
                        reference("eval-fixture"),
                        EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
                        measured,
                        outcome,
                        "reviewer",
                        NOW);
        EvidenceEvaluationSample replay = EvidenceEvaluationSample.capturedReplay(
                "eval-replay",
                "f".repeat(64),
                "diag-1",
                "CSDP",
                "session-svc",
                "message_send_failed",
                replayPreview(),
                "a".repeat(64),
                NOW,
                false,
                "admin",
                NOW)
                .finalizeReference(
                        reference("eval-replay"),
                        EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
                        measured,
                        outcome,
                        "reviewer",
                        NOW);
        when(fixture.store.list(7L, null, 100))
                .thenReturn(List.of(real, fixtureSample, replay));

        NorthStarComparison comparison = fixture.service.northStar(
                7L,
                null,
                100,
                List.of(
                        baselineRun("run-real", real, 100, 200),
                        baselineRun("run-fixture", fixtureSample, 100, 300),
                        baselineRun("run-replay", replay, 100, 400),
                        baselineRun(
                                "run-unmatched",
                                capturedGuance("eval-unmatched", "1".repeat(64), false),
                                100,
                                500)));

        assertThat(comparison.sampleCount()).isEqualTo(1);
        assertThat(comparison.withHumanBaseline()).isEqualTo(1);
        assertThat(comparison.measured().p50Minutes()).isEqualTo(40L);
        assertThat(comparison.machineRunCount()).isEqualTo(1);
        assertThat(comparison.machineP50Ms()).isEqualTo(300L);
        assertThat(comparison.caveats())
                .anyMatch(caveat -> caveat.contains("只统计真实 Guance 且非演练样本"));
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
        SopSynthesisService replay = mock(SopSynthesisService.class);
        RecordedReplayEvaluationCapabilityService replayCapability =
                mock(RecordedReplayEvaluationCapabilityService.class);
        GuanceEvidenceAcceptanceService acceptance =
                mock(GuanceEvidenceAcceptanceService.class);
        TroubleshootingPilotPlanService pilotPlans =
                mock(TroubleshootingPilotPlanService.class);
        TroubleshootingPlaybookVersionService playbookVersions =
                mock(TroubleshootingPlaybookVersionService.class);
        Diagnosis diagnosis = mock(Diagnosis.class);
        IncidentContext incident = mock(IncidentContext.class);
        when(incident.system()).thenReturn("CSDP");
        when(incident.service()).thenReturn("session-svc");
        when(incident.occurredAt()).thenReturn(NOW);
        when(diagnosis.diagnosisId()).thenReturn("diag-1");
        when(diagnosis.incident()).thenReturn(incident);
        when(diagnosis.fixtureMode()).thenReturn(diagnosisFixtureMode);
        when(diagnosis.rehearsal()).thenReturn(false);
        when(diagnosis.status()).thenReturn(status);
        when(diagnosis.closure()).thenReturn(closure);
        PlaybookVersionRef playbookRef =
                new PlaybookVersionRef("playbook-message-send", 3);
        when(diagnosis.sopKey()).thenReturn("csdp:701018");
        when(diagnosis.sourcePlaybookVersionRef()).thenReturn(playbookRef);
        when(persistence.get(7L, "diag-1"))
                .thenReturn(new StoredDiagnosis(diagnosis, 3, false, 2));
        ApprovedPlaybookVersion approved = mock(ApprovedPlaybookVersion.class);
        when(approved.selectorKey()).thenReturn("csdp:701018");
        when(approved.playbook()).thenReturn(formalPlaybook());
        when(playbookVersions.findByRef(7L, playbookRef)).thenReturn(Optional.of(approved));
        when(playbookVersions.activeRef(7L, "csdp:701018"))
                .thenReturn(Optional.of(playbookRef));
        when(pilotPlans.enrollmentVersion(7L, "CSDP", "session-svc", false))
                .thenReturn(2);
        when(acceptance.requireAccepted(7L, "CSDP", "session-svc"))
                .thenReturn(accepted(
                        "t7-012345678901234567890123", "a".repeat(64)));
        when(replayCapability.inspect(7L, "diag-1"))
                .thenReturn(new RecordedReplayEvaluationCapability(
                        true,
                        "READY",
                        "ready",
                        "message_send_failed",
                        "source_lookup_key",
                        "-15m"));
        return new Fixture(
                new EvidenceEvaluationSampleService(
                        preview,
                        persistence,
                        store,
                        new EvaluationModelInputFactory(
                                new ObjectMapper().findAndRegisterModules()),
                        replay,
                        replayCapability,
                        acceptance,
                        playbookVersions,
                        pilotPlans,
                        CLOCK),
                preview,
                replay,
                persistence,
                store,
                replayCapability,
                acceptance,
                playbookVersions,
                pilotPlans,
                diagnosis);
    }

    private GuanceEvidenceAcceptance accepted(
            String acceptanceId, String fingerprint) {
        return new GuanceEvidenceAcceptance(
                acceptanceId,
                "CSDP",
                "session-svc",
                fingerprint,
                new GuanceEvidenceAcceptance.Checklist(
                        true, true, true, true, true, true, true),
                new GuanceEvidenceAcceptance.ValidationFacts(
                        3, 2, "c".repeat(64), 10, 20, 35, NOW),
                "owner",
                NOW);
    }

    private SopEntry formalPlaybook() {
        return formalPlaybook("701018", "message_send_failed");
    }

    private SopEntry formalPlaybook(String errorCode, String searchTerm) {
        return new SopEntry(
                "playbook-message-send",
                SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP",
                errorCode,
                "session-svc",
                "会话消息发送失败",
                "",
                "message",
                "CSDP",
                "approved",
                true,
                List.of(
                        new EvidenceRequest(
                                "SEARCH",
                                "log_search",
                                "查找失败请求",
                                Map.of("search_term", searchTerm),
                                "-15m",
                                true),
                        new EvidenceRequest(
                                "TRACE",
                                "log_trace_bundle",
                                "关联调用链",
                                Map.of("ps_id", "server-owned"),
                                "-15m",
                                true),
                        new EvidenceRequest(
                                "CONTRAST",
                                "contrast_sample",
                                "对照正常样本",
                                Map.of(
                                        "scenario_key", searchTerm,
                                        "exclude_ps_id", "server-owned"),
                                "-15m",
                                true)),
                List.of(),
                List.of(),
                List.of());
    }

    private EvidenceEvaluationSample capturedSample(boolean diagnosisFixtureMode) {
        return EvidenceEvaluationSample.capturedFormal(
                "eval-012345678901234567890123",
                "a".repeat(64),
                "a".repeat(64),
                1,
                "diag-1",
                "CSDP",
                "session-svc",
                "message_send_failed",
                fullPreview(),
                "b".repeat(64),
                NOW,
                diagnosisFixtureMode,
                2,
                new PlaybookVersionRef("playbook-message-send", 3),
                "admin",
                NOW);
    }

    private EvidenceEvaluationSample capturedGuance(
            String sampleId,
            String sampleKey,
            boolean diagnosisFixtureMode) {
        return EvidenceEvaluationSample.capturedFormal(
                sampleId,
                sampleKey,
                sampleKey,
                1,
                "diag-1",
                "CSDP",
                "session-svc",
                "message_send_failed",
                fullPreview(),
                "b".repeat(64),
                NOW,
                diagnosisFixtureMode,
                2,
                new PlaybookVersionRef("playbook-message-send", 3),
                "admin",
                NOW);
    }

    private BaselineEvaluationRun baselineRun(
            String runId,
            EvidenceEvaluationSample sample,
            long evidenceDurationMs,
            long modelDurationMs) {
        return new BaselineEvaluationRun(
                runId,
                "2".repeat(64),
                sample.sampleId(),
                sample.diagnosisId(),
                Math.max(sample.version(), 1),
                sample.sourcePlatform(),
                sample.evidence().fixtureMode(),
                sample.diagnosisFixtureMode(),
                sample.evidence().stage(),
                "3".repeat(64),
                BaselineEvaluationRun.Status.SCORED,
                List.of(),
                new BaselineEvaluationRun.ValidationSnapshot(true, true, List.of()),
                new BaselineEvaluationRun.QualitySnapshot(
                        EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
                        BaselineEvaluationRun.ActualDisposition.DRAFT,
                        BaselineEvaluationRun.Classification.HELPFUL,
                        true,
                        1.0,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        false),
                new BaselineEvaluationRun.ModelSnapshot(
                        "provider",
                        "model",
                        "v1",
                        NOW,
                        1,
                        null,
                        null,
                        null),
                evidenceDurationMs,
                modelDurationMs,
                evidenceDurationMs + modelDurationMs,
                "reviewer",
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
                        true, "session_state_conflict",
                        100, 92, 100, 3, 0.92, 0.03, 0.89),
                3, 50L,
                List.of(
                        observed("log_search", "T8-GUANCE-LOG-SEARCH"),
                        observed("log_trace_bundle", "T8-GUANCE-TRACE-BUNDLE"),
                        observed("contrast_sample", "T8-GUANCE-CONTRAST-SAMPLE")),
                NOW, List.of("待 T7/T8 验收"));
    }

    private SopSynthesisPreview replayPreview() {
        LogTraceSkeleton skeleton = new LogTraceSkeleton(
                "ps-message-001",
                1_000,
                1_042,
                42,
                List.of("gateway", "session-svc"),
                List.of(
                        new LogTraceSkeleton.TimelineEvent(
                                0, 0, "gateway", "INFO", "request accepted", 1.0, false),
                        new LogTraceSkeleton.TimelineEvent(
                                1, 42, "session-svc", "ERROR", "state conflict", 42.0, true)),
                List.of(1),
                Map.of("session-svc", new LogTraceSkeleton.DurationSummary(1, 42, 42, 42)),
                2,
                0,
                new LogTraceSkeleton.ContrastSummary(
                        true, "state_conflict", 100, 92, 100, 3, 0.92, 0.03, 0.89));
        EvidenceSpineTimings timings = new EvidenceSpineTimings(10L, 20L, 5L, 5L);
        return new SopSynthesisPreview(
                SopSynthesisPreview.Stage.READY_FOR_MODEL,
                "CSDP",
                "session-svc",
                "source_lookup_key",
                4,
                "ps-message-001",
                replayEvidence("SYNTH-LOG-SEARCH"),
                replayEvidence("SYNTH-TRACE-BUNDLE"),
                replayEvidence("SYNTH-CONTRAST-SAMPLE"),
                skeleton,
                true,
                2,
                3,
                40,
                timings,
                NOW,
                List.of());
    }

    private SopSynthesisPreview.EvidenceReference replayEvidence(String queryId) {
        return new SopSynthesisPreview.EvidenceReference(
                queryId, EvidenceStatus.ANOMALY, "recorded-replay", NOW);
    }

    private GuanceEvidenceSpinePreview corePreview() {
        return new GuanceEvidenceSpinePreview(
                GuanceEvidenceSpinePreview.Stage.CORE_CHAIN_OBSERVED,
                readiness(),
                4L,
                "ps-message-001",
                3,
                List.of("gateway", "session-svc", "openim"),
                2,
                42L,
                GuanceEvidenceSpinePreview.Contrast.unavailable(),
                3,
                50L,
                List.of(
                        observed("log_search", "T8-GUANCE-LOG-SEARCH"),
                        observed("log_trace_bundle", "T8-GUANCE-TRACE-BUNDLE"),
                        missing("contrast_sample", "T8-GUANCE-CONTRAST-SAMPLE")),
                NOW,
                List.of("contrast unavailable"));
    }

    private GuanceEvidenceSpineObservation observation(
            GuanceEvidenceSpinePreview preview) {
        return new GuanceEvidenceSpineObservation(preview, new LogTraceSkeleton(
                "ps-message-001",
                1_000,
                1_042,
                42,
                List.of("gateway", "session-svc", "openim"),
                List.of(
                        new LogTraceSkeleton.TimelineEvent(
                                0, 0, "gateway", "INFO", "request accepted", 1.0, false),
                        new LogTraceSkeleton.TimelineEvent(
                                1, 42, "session-svc", "ERROR", "state conflict", 42.0, true)),
                List.of(1),
                Map.of("session-svc", new LogTraceSkeleton.DurationSummary(1, 42, 42, 42)),
                2,
                0,
                new LogTraceSkeleton.ContrastSummary(
                        true, "state_conflict", 100, 92, 100, 3, 0.92, 0.03, 0.89)));
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

    private GuanceEvidenceSpinePreview.Step missing(String kind, String ref) {
        return new GuanceEvidenceSpinePreview.Step(
                kind, GuanceEvidenceSpinePreview.StepStatus.MISSING, ref, null);
    }

    private record Fixture(
            EvidenceEvaluationSampleService service,
            GuanceEvidenceSpinePreviewService preview,
            SopSynthesisService replay,
            TroubleshootingPersistenceService persistence,
            EvidenceEvaluationSampleStore store,
            RecordedReplayEvaluationCapabilityService replayCapability,
            GuanceEvidenceAcceptanceService acceptance,
            TroubleshootingPlaybookVersionService playbookVersions,
            TroubleshootingPilotPlanService pilotPlans,
            Diagnosis diagnosis) {
    }
}
