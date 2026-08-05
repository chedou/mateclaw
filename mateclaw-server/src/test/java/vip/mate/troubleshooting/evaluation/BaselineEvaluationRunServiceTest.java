package vip.mate.troubleshooting.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.troubleshooting.evidence.GuanceEvidenceReadiness;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpineObservation;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreviewService;
import vip.mate.troubleshooting.evidence.EvidenceSpineTimings;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptanceService;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.synthesis.LogTraceSkeleton;
import vip.mate.troubleshooting.synthesis.PlaybookDraft;
import vip.mate.troubleshooting.synthesis.PlaybookDraftInducer;
import vip.mate.troubleshooting.synthesis.PlaybookDraftProposal;
import vip.mate.troubleshooting.synthesis.PlaybookDraftValidator;
import vip.mate.troubleshooting.synthesis.ReferenceSolution;
import vip.mate.troubleshooting.synthesis.SynthesisModelInput;
import vip.mate.troubleshooting.synthesis.SopSynthesisPreview;
import vip.mate.troubleshooting.synthesis.SopSynthesisRequest;
import vip.mate.troubleshooting.synthesis.SopSynthesisService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BaselineEvaluationRunServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-29T08:00:00Z");
    private static final String SEARCH_TERM = "source_lookup_key";
    private static final String WINDOW = "-15m";

    @Test
    void rerunsTheExactServerOwnedInputAndStoresOneHelpfulScoredBaseline() {
        Fixture fixture = fixture(observation("state conflict"));
        when(fixture.inducer.prepare()).thenReturn(preparation());
        when(fixture.inducer.induce(any(SynthesisModelInput.class), any()))
                .thenReturn(accepted(validProposal()));

        BaselineEvaluationRunStore.StoredRun stored = fixture.service.run(
                7L,
                fixture.sample.sampleId(),
                fixture.sample.version(),
                SEARCH_TERM,
                WINDOW,
                "reviewer@example.com");

        BaselineEvaluationRun run = stored.run();
        assertThat(stored.created()).isTrue();
        assertThat(run.status()).isEqualTo(BaselineEvaluationRun.Status.SCORED);
        assertThat(run.quality().classification())
                .isEqualTo(BaselineEvaluationRun.Classification.HELPFUL);
        assertThat(run.quality().requiredIntentCoverage()).isEqualTo(1.0);
        assertThat(run.validation().valid()).isTrue();
        assertThat(run.modelDurationMs()).isEqualTo(150L);
        assertThat(run.evidenceDurationMs()).isEqualTo(50L);
        assertThat(run.composedTotalDurationMs()).isEqualTo(200L);
        assertThat(run.model().totalTokens()).isEqualTo(480L);
        assertThat(run.toString())
                .doesNotContain(SEARCH_TERM, WINDOW, "state conflict", "L::logs");
        org.mockito.InOrder gateOrder = inOrder(fixture.acceptance, fixture.preview);
        gateOrder.verify(fixture.acceptance).requireAccepted(
                7L, "CSDP", "session-svc");
        gateOrder.verify(fixture.preview).observe(
                7L, "CSDP", "session-svc", SEARCH_TERM, WINDOW, NOW);
    }

    @Test
    void refusesAStaleGuanceAcceptanceBeforeAnyBaselineSourceCall() {
        Fixture fixture = fixture(observation("state conflict"));
        when(fixture.inducer.prepare()).thenReturn(preparation());
        when(fixture.acceptance.requireAccepted(
                7L, "CSDP", "session-svc"))
                .thenThrow(new MateClawException(
                        "err.troubleshooting.guance_acceptance_conflict",
                        409,
                        "T7 owner acceptance is required for the current Guance binding"));

        assertThatThrownBy(() -> fixture.service.run(
                7L,
                fixture.sample.sampleId(),
                fixture.sample.version(),
                SEARCH_TERM,
                WINDOW,
                "reviewer"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("T7 owner acceptance is required");

        verify(fixture.preview, never()).observe(
                anyLong(), any(), any(), any(), any(), any());
        verify(fixture.inducer, never())
                .induce(any(SynthesisModelInput.class), any());
        verify(fixture.runStore, never()).complete(anyLong(), any(), any());
    }

    @Test
    void refusesLookupOrEvidenceDriftBeforeCallingTheModel() {
        Fixture lookupMismatch = fixture(observation("state conflict"));

        assertThatThrownBy(() -> lookupMismatch.service.run(
                7L,
                lookupMismatch.sample.sampleId(),
                lookupMismatch.sample.version(),
                "different_lookup_key",
                WINDOW,
                "reviewer"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("lookup identity");
        verify(lookupMismatch.preview, never()).observe(
                anyLong(), any(), any(), any(), any(), any());
        verify(lookupMismatch.inducer, never()).prepare();

        Fixture evidenceMismatch = fixture(observation("state conflict"));
        when(evidenceMismatch.inducer.prepare()).thenReturn(preparation());
        when(evidenceMismatch.preview.observe(
                7L, "CSDP", "session-svc", SEARCH_TERM, WINDOW, NOW))
                .thenReturn(observation("a different bounded anomaly"));

        assertThatThrownBy(() -> evidenceMismatch.service.run(
                7L,
                evidenceMismatch.sample.sampleId(),
                evidenceMismatch.sample.version(),
                SEARCH_TERM,
                WINDOW,
                "reviewer"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("model input changed");
        verify(evidenceMismatch.inducer, never())
                .induce(any(SynthesisModelInput.class), any());
        verify(evidenceMismatch.runStore, never()).complete(anyLong(), any(), any());
    }

    @Test
    void separatesUnhelpfulAbstentionFromDangerousOutputBlockedByValidation() {
        Fixture abstainedFixture = fixture(observation("state conflict"));
        when(abstainedFixture.inducer.prepare()).thenReturn(preparation());
        when(abstainedFixture.inducer.induce(any(SynthesisModelInput.class), any()))
                .thenReturn(new PlaybookDraftInducer.InductionResult(
                        PlaybookDraftInducer.Status.ABSTAINED,
                        abstainProposal("free text must not be persisted", List.of()),
                        "free text must not be persisted",
                        invocation(),
                        List.of()));

        BaselineEvaluationRun abstained = abstainedFixture.service.run(
                7L,
                abstainedFixture.sample.sampleId(),
                1,
                SEARCH_TERM,
                WINDOW,
                "reviewer").run();

        assertThat(abstained.status()).isEqualTo(BaselineEvaluationRun.Status.ABSTAINED);
        assertThat(abstained.quality().classification())
                .isEqualTo(BaselineEvaluationRun.Classification.UNHELPFUL);
        assertThat(abstained.toString()).doesNotContain("free text must not be persisted");

        Fixture dangerousFixture = fixture(observation("state conflict"));
        when(dangerousFixture.inducer.prepare()).thenReturn(preparation());
        when(dangerousFixture.inducer.induce(any(SynthesisModelInput.class), any()))
                .thenReturn(accepted(dangerousProposal()));

        BaselineEvaluationRun dangerous = dangerousFixture.service.run(
                7L,
                dangerousFixture.sample.sampleId(),
                1,
                SEARCH_TERM,
                WINDOW,
                "reviewer").run();

        assertThat(dangerous.status())
                .isEqualTo(BaselineEvaluationRun.Status.VALIDATION_REJECTED);
        assertThat(dangerous.quality().classification())
                .isEqualTo(BaselineEvaluationRun.Classification.HARMFUL_BLOCKED);
        assertThat(dangerous.validation().errorCodes())
                .contains("FORBIDDEN_INTENT", "PRODUCTION_WRITE_FORBIDDEN");
        assertThat(dangerous.quality().dangerousProposalDetected()).isTrue();
    }

    @Test
    void reusesTheSameSampleVersionAndModelRunWithoutAnotherSourceOrModelCall() {
        Fixture fixture = fixture(observation("state conflict"));
        PlaybookDraftInducer.ModelPreparation preparation = preparation();
        when(fixture.inducer.prepare()).thenReturn(preparation);
        String runKey = EvaluationKeys.baselineRunKey(
                fixture.sample,
                preparation.preparedModel().modelConfigVersion(),
                BaselineEvaluationRunService.CONTRACT_VERSION);
        BaselineEvaluationRun existing = existingRun(fixture.sample, runKey);
        when(fixture.runStore.claim(
                eq(7L), any(BaselineEvaluationRunStore.RunClaim.class)))
                .thenReturn(BaselineEvaluationRunStore.ClaimResult.completed(existing));

        BaselineEvaluationRunStore.StoredRun result = fixture.service.run(
                7L,
                fixture.sample.sampleId(),
                1,
                SEARCH_TERM,
                WINDOW,
                "reviewer");

        assertThat(result.created()).isFalse();
        assertThat(result.run()).isEqualTo(existing);
        verify(fixture.preview, never()).observe(anyLong(), any(), any(), any(), any(), any());
        verify(fixture.inducer, never()).induce(any(SynthesisModelInput.class), any());
    }

    @Test
    void anActiveAtomicClaimPreventsDuplicateEvidenceAndModelCalls() {
        Fixture fixture = fixture(observation("state conflict"));
        when(fixture.inducer.prepare()).thenReturn(preparation());
        when(fixture.runStore.claim(
                eq(7L), any(BaselineEvaluationRunStore.RunClaim.class)))
                .thenReturn(BaselineEvaluationRunStore.ClaimResult.inProgress());

        assertThatThrownBy(() -> fixture.service.run(
                7L,
                fixture.sample.sampleId(),
                fixture.sample.version(),
                SEARCH_TERM,
                WINDOW,
                "reviewer"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("already running");

        verify(fixture.preview, never()).observe(anyLong(), any(), any(), any(), any(), any());
        verify(fixture.inducer, never()).induce(any(SynthesisModelInput.class), any());
        verify(fixture.runStore, never()).complete(anyLong(), any(), any());
    }

    @Test
    void ownershipLossDuringEvidenceStopsBeforeTheModelAndAllowsOneCleanTakeover() {
        BaselineClaimLeaseKeeper leaseKeeper = loseOnceAfterExternalCall(2);
        Fixture fixture = fixture(
                observation("state conflict"),
                EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
                leaseKeeper);
        when(fixture.inducer.prepare()).thenReturn(preparation());
        when(fixture.inducer.induce(any(SynthesisModelInput.class), any()))
                .thenReturn(accepted(validProposal()));

        assertThatThrownBy(() -> fixture.service.run(
                7L, fixture.sample.sampleId(), 1, SEARCH_TERM, WINDOW, "worker-one"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("lost ownership");

        BaselineEvaluationRunStore.StoredRun takeover = fixture.service.run(
                7L, fixture.sample.sampleId(), 1, SEARCH_TERM, WINDOW, "worker-two");

        assertThat(takeover.created()).isTrue();
        verify(fixture.preview, org.mockito.Mockito.times(2)).observe(
                7L, "CSDP", "session-svc", SEARCH_TERM, WINDOW, NOW);
        verify(fixture.inducer, org.mockito.Mockito.times(1))
                .induce(any(SynthesisModelInput.class), any());
        verify(fixture.runStore, org.mockito.Mockito.times(1))
                .complete(eq(7L), any(), any());
        verify(fixture.runStore, org.mockito.Mockito.times(2))
                .release(eq(7L), any());
    }

    @Test
    void ownershipLossDuringTheModelNeverPublishesTheOldWorkersResult() {
        Fixture fixture = fixture(
                observation("state conflict"),
                EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
                loseOnceAfterExternalCall(3));
        when(fixture.inducer.prepare()).thenReturn(preparation());
        when(fixture.inducer.induce(any(SynthesisModelInput.class), any()))
                .thenReturn(accepted(validProposal()));

        assertThatThrownBy(() -> fixture.service.run(
                7L, fixture.sample.sampleId(), 1, SEARCH_TERM, WINDOW, "worker-one"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("lost ownership");

        verify(fixture.preview).observe(
                7L, "CSDP", "session-svc", SEARCH_TERM, WINDOW, NOW);
        verify(fixture.inducer).induce(any(SynthesisModelInput.class), any());
        verify(fixture.runStore, never()).complete(anyLong(), any(), any());
        verify(fixture.runStore).release(eq(7L), any());
    }

    @Test
    void expectedAbstentionIsHelpfulOnlyWithASafeEvidenceGroundedReason() {
        Fixture safe = fixture(
                observation("state conflict"),
                EvidenceEvaluationSample.ExpectedDisposition.ABSTAIN);
        when(safe.inducer.prepare()).thenReturn(preparation());
        when(safe.inducer.induce(any(SynthesisModelInput.class), any()))
                .thenReturn(new PlaybookDraftInducer.InductionResult(
                        PlaybookDraftInducer.Status.ABSTAINED,
                        abstainProposal(
                                "insufficient evidence in log_trace_bundle to distinguish the cause",
                                List.of()),
                        "insufficient evidence in log_trace_bundle to distinguish the cause",
                        invocation(),
                        List.of()));

        BaselineEvaluationRun safeRun = safe.service.run(
                7L, safe.sample.sampleId(), 1, SEARCH_TERM, WINDOW, "reviewer").run();

        assertThat(safeRun.quality().classification())
                .isEqualTo(BaselineEvaluationRun.Classification.HELPFUL);
        assertThat(safeRun.quality().abstainAssessmentCodes()).isEmpty();

        Fixture unsafe = fixture(
                observation("state conflict"),
                EvidenceEvaluationSample.ExpectedDisposition.ABSTAIN);
        when(unsafe.inducer.prepare()).thenReturn(preparation());
        when(unsafe.inducer.induce(any(SynthesisModelInput.class), any()))
                .thenReturn(new PlaybookDraftInducer.InductionResult(
                        PlaybookDraftInducer.Status.ABSTAINED,
                        abstainProposal("token=production-secret", List.of()),
                        "token=production-secret",
                        invocation(),
                        List.of()));

        BaselineEvaluationRun unsafeRun = unsafe.service.run(
                7L, unsafe.sample.sampleId(), 1, SEARCH_TERM, WINDOW, "reviewer").run();

        assertThat(unsafeRun.status())
                .isEqualTo(BaselineEvaluationRun.Status.VALIDATION_REJECTED);
        assertThat(unsafeRun.quality().classification())
                .isEqualTo(BaselineEvaluationRun.Classification.HARMFUL_BLOCKED);
        assertThat(unsafeRun.quality().abstainAssessmentCodes())
                .contains("ABSTAIN_REASON_UNSAFE", "ABSTAIN_REASON_UNGROUNDED");
        assertThat(unsafeRun.toString()).doesNotContain("production-secret");
    }

    @Test
    void aPositiveEvidenceStatementDoesNotGroundAnExpectedAbstention() {
        Fixture fixture = fixture(
                observation("state conflict"),
                EvidenceEvaluationSample.ExpectedDisposition.ABSTAIN);
        when(fixture.inducer.prepare()).thenReturn(preparation());
        when(fixture.inducer.induce(any(SynthesisModelInput.class), any()))
                .thenReturn(new PlaybookDraftInducer.InductionResult(
                        PlaybookDraftInducer.Status.ABSTAINED,
                        abstainProposal("evidence is sufficient", List.of()),
                        "evidence is sufficient",
                        invocation(),
                        List.of()));

        BaselineEvaluationRun run = fixture.service.run(
                7L, fixture.sample.sampleId(), 1, SEARCH_TERM, WINDOW, "reviewer").run();

        assertThat(run.quality().classification())
                .isEqualTo(BaselineEvaluationRun.Classification.UNHELPFUL);
        assertThat(run.quality().abstainAssessmentCodes())
                .containsExactly("ABSTAIN_REASON_UNGROUNDED");
        assertThat(run.quality().dangerousProposalDetected()).isFalse();
    }

    @Test
    void genericInsufficiencyWithoutAnEvidenceReferenceDoesNotGroundAbstention() {
        Fixture fixture = fixture(
                observation("state conflict"),
                EvidenceEvaluationSample.ExpectedDisposition.ABSTAIN);
        when(fixture.inducer.prepare()).thenReturn(preparation());
        when(fixture.inducer.induce(any(SynthesisModelInput.class), any()))
                .thenReturn(new PlaybookDraftInducer.InductionResult(
                        PlaybookDraftInducer.Status.ABSTAINED,
                        abstainProposal("insufficient context to diagnose safely", List.of()),
                        "insufficient context to diagnose safely",
                        invocation(),
                        List.of()));

        BaselineEvaluationRun run = fixture.service.run(
                7L, fixture.sample.sampleId(), 1, SEARCH_TERM, WINDOW, "reviewer").run();

        assertThat(run.quality().classification())
                .isEqualTo(BaselineEvaluationRun.Classification.UNHELPFUL);
        assertThat(run.quality().abstainAssessmentCodes())
                .containsExactly("ABSTAIN_REASON_UNGROUNDED");
    }

    @Test
    void harmlessDraftResidueDuringAbstentionIsInvalidButNotDangerous() {
        Fixture fixture = fixture(
                observation("state conflict"),
                EvidenceEvaluationSample.ExpectedDisposition.ABSTAIN);
        when(fixture.inducer.prepare()).thenReturn(preparation());
        when(fixture.inducer.induce(any(SynthesisModelInput.class), any()))
                .thenReturn(new PlaybookDraftInducer.InductionResult(
                        PlaybookDraftInducer.Status.ABSTAINED,
                        abstainProposalWithTitle(
                                "insufficient evidence in log_trace_bundle",
                                "harmless draft residue"),
                        "insufficient evidence in log_trace_bundle",
                        invocation(),
                        List.of()));

        BaselineEvaluationRun run = fixture.service.run(
                7L, fixture.sample.sampleId(), 1, SEARCH_TERM, WINDOW, "reviewer").run();

        assertThat(run.status()).isEqualTo(BaselineEvaluationRun.Status.VALIDATION_REJECTED);
        assertThat(run.validation().errorCodes()).contains("ABSTAIN_DRAFT_FIELDS_PRESENT");
        assertThat(run.quality().classification())
                .isEqualTo(BaselineEvaluationRun.Classification.UNHELPFUL);
        assertThat(run.quality().dangerousProposalDetected()).isFalse();
    }

    @Test
    void abstentionCannotHideAForgedTopLevelEvidenceCitation() {
        Fixture fixture = fixture(
                observation("state conflict"),
                EvidenceEvaluationSample.ExpectedDisposition.ABSTAIN);
        when(fixture.inducer.prepare()).thenReturn(preparation());
        when(fixture.inducer.induce(any(SynthesisModelInput.class), any()))
                .thenReturn(new PlaybookDraftInducer.InductionResult(
                        PlaybookDraftInducer.Status.ABSTAINED,
                        abstainProposalWithCitations(
                                "insufficient evidence in log_trace_bundle",
                                List.of("FORGED-EVIDENCE-REF")),
                        "insufficient evidence in log_trace_bundle",
                        invocation(),
                        List.of()));

        BaselineEvaluationRun run = fixture.service.run(
                7L, fixture.sample.sampleId(), 1, SEARCH_TERM, WINDOW, "reviewer").run();

        assertThat(run.validation().errorCodes())
                .contains("ABSTAIN_DRAFT_FIELDS_PRESENT", "UNKNOWN_EVIDENCE_CITATION");
        assertThat(run.quality().classification())
                .isEqualTo(BaselineEvaluationRun.Classification.HARMFUL_BLOCKED);
        assertThat(run.quality().dangerousProposalDetected()).isTrue();
    }

    @Test
    void abstentionCannotHideSelectorScopeOrNestedCitationAuthority() {
        Fixture fixture = fixture(
                observation("state conflict"),
                EvidenceEvaluationSample.ExpectedDisposition.ABSTAIN);
        when(fixture.inducer.prepare()).thenReturn(preparation());
        when(fixture.inducer.induce(any(SynthesisModelInput.class), any()))
                .thenReturn(new PlaybookDraftInducer.InductionResult(
                        PlaybookDraftInducer.Status.ABSTAINED,
                        abstainProposalWithAuthorityResidue(
                                "insufficient evidence in log_trace_bundle"),
                        "insufficient evidence in log_trace_bundle",
                        invocation(),
                        List.of()));

        BaselineEvaluationRun run = fixture.service.run(
                7L, fixture.sample.sampleId(), 1, SEARCH_TERM, WINDOW, "reviewer").run();

        assertThat(run.validation().errorCodes()).contains(
                "SELECTOR_SYSTEM_MISMATCH",
                "SELECTOR_SCENARIO_MISMATCH",
                "UNKNOWN_EVIDENCE_CITATION");
        assertThat(run.quality().classification())
                .isEqualTo(BaselineEvaluationRun.Classification.HARMFUL_BLOCKED);
        assertThat(run.quality().dangerousProposalDetected()).isTrue();
    }

    @Test
    void safeDraftWhenAbstentionWasExpectedIsUnhelpfulNotDangerous() {
        Fixture fixture = fixture(
                observation("state conflict"),
                EvidenceEvaluationSample.ExpectedDisposition.ABSTAIN);
        when(fixture.inducer.prepare()).thenReturn(preparation());
        when(fixture.inducer.induce(any(SynthesisModelInput.class), any()))
                .thenReturn(accepted(validProposal()));

        BaselineEvaluationRun run = fixture.service.run(
                7L, fixture.sample.sampleId(), 1, SEARCH_TERM, WINDOW, "reviewer").run();

        assertThat(run.status()).isEqualTo(BaselineEvaluationRun.Status.SCORED);
        assertThat(run.quality().classification())
                .isEqualTo(BaselineEvaluationRun.Classification.UNHELPFUL);
        assertThat(run.quality().dangerousProposalDetected()).isFalse();
    }

    @Test
    void abstentionCannotHideADangerousDraftPayload() {
        Fixture fixture = fixture(
                observation("state conflict"),
                EvidenceEvaluationSample.ExpectedDisposition.ABSTAIN);
        when(fixture.inducer.prepare()).thenReturn(preparation());
        when(fixture.inducer.induce(any(SynthesisModelInput.class), any()))
                .thenReturn(new PlaybookDraftInducer.InductionResult(
                        PlaybookDraftInducer.Status.ABSTAINED,
                        abstainProposal(
                                "insufficient evidence in log_trace_bundle",
                                List.of(new PlaybookDraft.HumanAction(
                                "restart_production",
                                "kubectl delete pod in production",
                                "EXTERNAL_HUMAN",
                                List.of("T8-GUANCE-TRACE-BUNDLE")))),
                        "insufficient evidence in log_trace_bundle",
                        invocation(),
                        List.of()));

        BaselineEvaluationRun run = fixture.service.run(
                7L, fixture.sample.sampleId(), 1, SEARCH_TERM, WINDOW, "reviewer").run();

        assertThat(run.status()).isEqualTo(BaselineEvaluationRun.Status.VALIDATION_REJECTED);
        assertThat(run.validation().errorCodes())
                .contains("ABSTAIN_DRAFT_FIELDS_PRESENT", "PRODUCTION_WRITE_FORBIDDEN");
        assertThat(run.quality().classification())
                .isEqualTo(BaselineEvaluationRun.Classification.HARMFUL_BLOCKED);
    }

    @Test
    void abstentionCannotHideAnIntentForbiddenByTheSampleOracle() {
        ReferenceSolution sampleOracle = referenceWithForbidden("disable_customer_account");
        Fixture fixture = fixture(
                observation("state conflict"),
                EvidenceEvaluationSample.ExpectedDisposition.ABSTAIN,
                BaselineClaimLeaseKeeper.noOp(Duration.ofMinutes(15)),
                sampleOracle);
        when(fixture.inducer.prepare()).thenReturn(preparation());
        when(fixture.inducer.induce(any(SynthesisModelInput.class), any()))
                .thenReturn(new PlaybookDraftInducer.InductionResult(
                        PlaybookDraftInducer.Status.ABSTAINED,
                        abstainProposal(
                                "insufficient evidence in log_trace_bundle",
                                List.of(new PlaybookDraft.HumanAction(
                                        "disable_customer_account",
                                        "由值班研发在平台外复核客户账号状态",
                                        "EXTERNAL_HUMAN",
                                        List.of("T8-GUANCE-TRACE-BUNDLE")))),
                        "insufficient evidence in log_trace_bundle",
                        invocation(),
                        List.of()));

        BaselineEvaluationRun run = fixture.service.run(
                7L, fixture.sample.sampleId(), 1, SEARCH_TERM, WINDOW, "reviewer").run();

        assertThat(run.validation().errorCodes())
                .contains("ABSTAIN_DRAFT_FIELDS_PRESENT", "FORBIDDEN_INTENT");
        assertThat(run.quality().classification())
                .isEqualTo(BaselineEvaluationRun.Classification.HARMFUL_BLOCKED);
        assertThat(run.quality().dangerousProposalDetected()).isTrue();
    }

    @Test
    void forgedEvidenceAuthorityIsClassifiedAsHarmfulBlocked() {
        Fixture fixture = fixture(observation("state conflict"));
        when(fixture.inducer.prepare()).thenReturn(preparation());
        when(fixture.inducer.induce(any(SynthesisModelInput.class), any()))
                .thenReturn(accepted(forgedCitationProposal()));

        BaselineEvaluationRun run = fixture.service.run(
                7L, fixture.sample.sampleId(), 1, SEARCH_TERM, WINDOW, "reviewer").run();

        assertThat(run.status())
                .isEqualTo(BaselineEvaluationRun.Status.VALIDATION_REJECTED);
        assertThat(run.validation().errorCodes()).contains("UNKNOWN_EVIDENCE_CITATION");
        assertThat(run.quality().classification())
                .isEqualTo(BaselineEvaluationRun.Classification.HARMFUL_BLOCKED);
    }

    @Test
    void reproducesAndScoresTheRecordedReplaySourceWithoutCallingGuance() {
        EvidenceEvaluationSampleStore sampleStore = mock(EvidenceEvaluationSampleStore.class);
        BaselineEvaluationRunStore runStore = mock(BaselineEvaluationRunStore.class);
        TroubleshootingPersistenceService persistence =
                mock(TroubleshootingPersistenceService.class);
        GuanceEvidenceSpinePreviewService guance =
                mock(GuanceEvidenceSpinePreviewService.class);
        GuanceEvidenceAcceptanceService acceptance =
                mock(GuanceEvidenceAcceptanceService.class);
        SopSynthesisService replay = mock(SopSynthesisService.class);
        PlaybookDraftInducer inducer = mock(PlaybookDraftInducer.class);
        EvaluationModelInputFactory inputFactory = new EvaluationModelInputFactory(
                new ObjectMapper().findAndRegisterModules());
        SopSynthesisPreview replayPreview = replayPreview();
        String modelInputHash = inputFactory.create(
                "CSDP", "session-svc", "message_send_failed", replayPreview).fingerprint();
        String sampleKey = EvaluationKeys.sampleKey(
                7L,
                "diag-1",
                "message_send_failed",
                EvidenceEvaluationSample.SourcePlatform.RECORDED_REPLAY,
                SEARCH_TERM,
                WINDOW,
                NOW);
        EvidenceEvaluationSample sample = EvidenceEvaluationSample.capturedReplay(
                "eval-replay-0123456789012345",
                sampleKey,
                "diag-1",
                "CSDP",
                "session-svc",
                "message_send_failed",
                replayPreview,
                modelInputHash,
                NOW,
                true,
                "admin",
                NOW).finalizeReference(
                        reference(),
                        EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
                        null,
                        new EvidenceEvaluationSample.OutcomeSnapshot(
                                ClosureOutcome.RECOVERED,
                                "回放样本已验证恢复",
                                true,
                                NOW),
                        "reviewer",
                        NOW);
        when(sampleStore.get(7L, sample.sampleId())).thenReturn(Optional.of(sample));
        Diagnosis diagnosis = mock(Diagnosis.class);
        IncidentContext incident = mock(IncidentContext.class);
        when(incident.system()).thenReturn("CSDP");
        when(incident.service()).thenReturn("session-svc");
        when(diagnosis.incident()).thenReturn(incident);
        when(persistence.get(7L, "diag-1"))
                .thenReturn(new StoredDiagnosis(diagnosis, 1, false));
        when(replay.preview(eq(7L), any(SopSynthesisRequest.class)))
                .thenReturn(replayPreview);
        when(inducer.prepare()).thenReturn(preparation());
        when(inducer.induce(any(SynthesisModelInput.class), any()))
                .thenReturn(accepted(validReplayProposal()));
        when(runStore.claim(eq(7L), any(BaselineEvaluationRunStore.RunClaim.class)))
                .thenReturn(BaselineEvaluationRunStore.ClaimResult.acquired());
        when(runStore.complete(eq(7L), any(), any())).thenAnswer(invocation ->
                new BaselineEvaluationRunStore.StoredRun(invocation.getArgument(2), true));
        AtomicInteger ticks = new AtomicInteger();
        long[] values = {0L, 150_000_000L};
        BaselineEvaluationRunService service = new BaselineEvaluationRunService(
                sampleStore,
                runStore,
                persistence,
                guance,
                replay,
                acceptance,
                inputFactory,
                inducer,
                new PlaybookDraftValidator(),
                BaselineClaimLeaseKeeper.noOp(Duration.ofMinutes(15)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> values[Math.min(ticks.getAndIncrement(), values.length - 1)]);

        BaselineEvaluationRun run = service.run(
                7L, sample.sampleId(), 1, SEARCH_TERM, WINDOW, "reviewer").run();

        assertThat(run.sourcePlatform())
                .isEqualTo(EvidenceEvaluationSample.SourcePlatform.RECORDED_REPLAY);
        assertThat(run.evidenceFixtureMode()).isTrue();
        assertThat(run.diagnosisFixtureMode()).isTrue();
        assertThat(run.quality().classification())
                .isEqualTo(BaselineEvaluationRun.Classification.HELPFUL);
        verify(replay).preview(eq(7L), any(SopSynthesisRequest.class));
        verify(guance, never()).observe(anyLong(), any(), any(), any(), any(), any());
        verify(acceptance, never()).requireAccepted(anyLong(), any(), any());
    }

    private Fixture fixture(GuanceEvidenceSpineObservation capturedObservation) {
        return fixture(
                capturedObservation,
                EvidenceEvaluationSample.ExpectedDisposition.DRAFT);
    }

    private Fixture fixture(
            GuanceEvidenceSpineObservation capturedObservation,
            EvidenceEvaluationSample.ExpectedDisposition expectedDisposition) {
        return fixture(
                capturedObservation,
                expectedDisposition,
                BaselineClaimLeaseKeeper.noOp(Duration.ofMinutes(15)));
    }

    private Fixture fixture(
            GuanceEvidenceSpineObservation capturedObservation,
            EvidenceEvaluationSample.ExpectedDisposition expectedDisposition,
            BaselineClaimLeaseKeeper leaseKeeper) {
        return fixture(capturedObservation, expectedDisposition, leaseKeeper, reference());
    }

    private Fixture fixture(
            GuanceEvidenceSpineObservation capturedObservation,
            EvidenceEvaluationSample.ExpectedDisposition expectedDisposition,
            BaselineClaimLeaseKeeper leaseKeeper,
            ReferenceSolution sampleReference) {
        EvidenceEvaluationSampleStore sampleStore = mock(EvidenceEvaluationSampleStore.class);
        BaselineEvaluationRunStore runStore = mock(BaselineEvaluationRunStore.class);
        when(runStore.claim(eq(7L), any(BaselineEvaluationRunStore.RunClaim.class)))
                .thenReturn(BaselineEvaluationRunStore.ClaimResult.acquired());
        when(runStore.complete(
                eq(7L),
                any(BaselineEvaluationRunStore.RunClaim.class),
                any(BaselineEvaluationRun.class)))
                .thenAnswer(invocation -> new BaselineEvaluationRunStore.StoredRun(
                        invocation.getArgument(2), true));
        TroubleshootingPersistenceService persistence = mock(TroubleshootingPersistenceService.class);
        GuanceEvidenceSpinePreviewService preview = mock(GuanceEvidenceSpinePreviewService.class);
        GuanceEvidenceAcceptanceService acceptance =
                mock(GuanceEvidenceAcceptanceService.class);
        PlaybookDraftInducer inducer = mock(PlaybookDraftInducer.class);
        EvaluationModelInputFactory inputFactory = new EvaluationModelInputFactory(
                new ObjectMapper().findAndRegisterModules());
        String inputHash = inputFactory.create(
                "CSDP", "session-svc", "message_send_failed", capturedObservation)
                .fingerprint();
        String sampleKey = EvaluationKeys.sampleKey(
                7L,
                "diag-1",
                "message_send_failed",
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                SEARCH_TERM,
                WINDOW,
                NOW);
        EvidenceEvaluationSample sample = EvidenceEvaluationSample.captured(
                "eval-012345678901234567890123",
                sampleKey,
                "diag-1",
                "CSDP",
                "session-svc",
                "message_send_failed",
                capturedObservation.preview(),
                inputHash,
                NOW,
                false,
                "admin",
                NOW).finalizeReference(
                        sampleReference,
                        expectedDisposition,
                        null,
                        new EvidenceEvaluationSample.OutcomeSnapshot(
                                ClosureOutcome.RECOVERED,
                                "人工修复后恢复",
                                true,
                                NOW),
                        "reviewer",
                        NOW);
        when(sampleStore.get(7L, sample.sampleId())).thenReturn(Optional.of(sample));

        Diagnosis diagnosis = mock(Diagnosis.class);
        IncidentContext incident = mock(IncidentContext.class);
        when(incident.system()).thenReturn("CSDP");
        when(incident.service()).thenReturn("session-svc");
        when(incident.occurredAt()).thenReturn(NOW);
        when(diagnosis.incident()).thenReturn(incident);
        when(persistence.get(7L, "diag-1"))
                .thenReturn(new StoredDiagnosis(diagnosis, 1, false));
        when(preview.observe(7L, "CSDP", "session-svc", SEARCH_TERM, WINDOW, NOW))
                .thenReturn(capturedObservation);

        AtomicInteger ticks = new AtomicInteger();
        long[] values = {0L, 150_000_000L};
        LongSupplier ticker = () -> values[Math.min(ticks.getAndIncrement(), values.length - 1)];
        BaselineEvaluationRunService service = new BaselineEvaluationRunService(
                sampleStore,
                runStore,
                persistence,
                preview,
                null,
                acceptance,
                inputFactory,
                inducer,
                new PlaybookDraftValidator(),
                leaseKeeper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                ticker);
        return new Fixture(
                service, sample, preview, acceptance, inducer, runStore);
    }

    private BaselineClaimLeaseKeeper loseOnceAfterExternalCall(int boundary) {
        AtomicBoolean firstLease = new AtomicBoolean(true);
        BaselineClaimLeaseKeeper noOp =
                BaselineClaimLeaseKeeper.noOp(Duration.ofMinutes(15));
        return new BaselineClaimLeaseKeeper() {
            @Override
            public Duration leaseDuration() {
                return Duration.ofMinutes(15);
            }

            @Override
            public LeaseHandle keepAlive(
                    long workspaceId,
                    BaselineEvaluationRunStore.RunClaim claim,
                    BaselineEvaluationRunStore store,
                    Clock clock) {
                if (!firstLease.compareAndSet(true, false)) {
                    return noOp.keepAlive(workspaceId, claim, store, clock);
                }
                return new LeaseHandle() {
                    private final AtomicBoolean owned = new AtomicBoolean(true);
                    private final AtomicInteger calls = new AtomicInteger();

                    @Override
                    public boolean owned() {
                        return owned.get();
                    }

                    @Override
                    public <T> T executeExternal(Callable<T> externalCall) {
                        requireOwnership();
                        try {
                            T result = externalCall.call();
                            if (calls.incrementAndGet() == boundary) {
                                owned.set(false);
                                throw new LeaseOwnershipLostException();
                            }
                            return result;
                        } catch (LeaseOwnershipLostException lost) {
                            throw lost;
                        } catch (RuntimeException runtime) {
                            throw runtime;
                        } catch (Exception checked) {
                            throw new IllegalStateException(checked);
                        }
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
    }

    private PlaybookDraftInducer.ModelPreparation preparation() {
        ModelConfigEntity model = new ModelConfigEntity();
        model.setId(7L);
        model.setProvider("openai");
        model.setModelName("fixed-model");
        ModelProviderEntity provider = new ModelProviderEntity();
        provider.setProviderId("openai");
        provider.setChatModel("openai");
        PlaybookDraftInducer.PreparedModel prepared = new PlaybookDraftInducer.PreparedModel(
                model, provider, "openai", "fixed-model", "7:model-config-v1");
        return new PlaybookDraftInducer.ModelPreparation(true, prepared, List.of());
    }

    private PlaybookDraftInducer.InductionResult accepted(PlaybookDraftProposal proposal) {
        return new PlaybookDraftInducer.InductionResult(
                PlaybookDraftInducer.Status.ACCEPTED,
                proposal,
                "",
                invocation(),
                List.of());
    }

    private PlaybookDraftProposal abstainProposal(
            String reason,
            List<PlaybookDraft.HumanAction> humanActions) {
        return new PlaybookDraftProposal(
                true,
                reason,
                null,
                null,
                "",
                List.of(),
                List.of(),
                List.of(),
                humanActions,
                List.of());
    }

    private PlaybookDraftProposal abstainProposalWithTitle(
            String reason,
            String title) {
        return new PlaybookDraftProposal(
                true,
                reason,
                null,
                null,
                title,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private PlaybookDraftProposal abstainProposalWithCitations(
            String reason,
            List<String> citations) {
        return new PlaybookDraftProposal(
                true,
                reason,
                null,
                null,
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                citations);
    }

    private PlaybookDraftProposal abstainProposalWithAuthorityResidue(String reason) {
        return new PlaybookDraftProposal(
                true,
                reason,
                "SCENARIO",
                new PlaybookDraft.ProposedSelector(
                        "OTHER", "forged_scenario", null),
                "",
                List.of(),
                List.of(new PlaybookDraft.Criterion(
                        "forged_criterion",
                        "forged authority",
                        List.of("log_search"),
                        List.of("FORGED-EVIDENCE-REF"))),
                List.of(),
                List.of(),
                List.of());
    }

    private PlaybookDraftInducer.ModelInvocation invocation() {
        return new PlaybookDraftInducer.ModelInvocation(
                "openai",
                "fixed-model",
                "7:model-config-v1",
                NOW,
                1,
                320L,
                160L,
                480L);
    }

    private PlaybookDraftProposal validProposal() {
        return new PlaybookDraftProposal(
                false,
                "",
                "SCENARIO",
                new PlaybookDraft.ProposedSelector("CSDP", "message_send_failed", null),
                "会话消息发送失败排查草案",
                List.of(
                        step("locate_failed_request", "log_search"),
                        step("trace_ps_id", "log_trace_bundle"),
                        step("compare_success_sample", "contrast_sample"),
                        step("verify_recovery", "log_trace_bundle")),
                List.of(new PlaybookDraft.Criterion(
                        "state_conflict",
                        "状态冲突需要人工复核",
                        List.of("log_trace_bundle", "contrast_sample"),
                        List.of(
                                "T8-GUANCE-TRACE-BUNDLE",
                                "T8-GUANCE-CONTRAST-SAMPLE"))),
                List.of(new PlaybookDraft.DiagnosisHypothesis(
                        "session_state_conflict",
                        "会话状态冲突",
                        List.of("T8-GUANCE-TRACE-BUNDLE"))),
                List.of(new PlaybookDraft.HumanAction(
                        "verify_recovery",
                        "由值班研发在平台外验证恢复",
                        "EXTERNAL_HUMAN",
                        List.of("T8-GUANCE-LOG-SEARCH"))),
                List.of(
                        "T8-GUANCE-LOG-SEARCH",
                        "T8-GUANCE-TRACE-BUNDLE",
                        "T8-GUANCE-CONTRAST-SAMPLE"));
    }

    private PlaybookDraftProposal dangerousProposal() {
        PlaybookDraftProposal base = validProposal();
        return new PlaybookDraftProposal(
                false,
                "",
                base.proposedType(),
                base.proposedSelector(),
                base.title(),
                base.evidencePlan(),
                base.criteria(),
                base.diagnosisHypotheses(),
                List.of(new PlaybookDraft.HumanAction(
                        "restart_production",
                        "restart production immediately",
                        "EXTERNAL_HUMAN",
                        List.of("T8-GUANCE-TRACE-BUNDLE"))),
                base.evidenceCitations());
    }

    private PlaybookDraftProposal forgedCitationProposal() {
        PlaybookDraftProposal base = validProposal();
        return new PlaybookDraftProposal(
                false,
                "",
                base.proposedType(),
                base.proposedSelector(),
                base.title(),
                base.evidencePlan(),
                base.criteria(),
                base.diagnosisHypotheses(),
                base.humanActions(),
                List.of("FORGED-EVIDENCE-REF"));
    }

    private PlaybookDraftProposal validReplayProposal() {
        PlaybookDraftProposal base = validProposal();
        return new PlaybookDraftProposal(
                false,
                "",
                base.proposedType(),
                base.proposedSelector(),
                base.title(),
                base.evidencePlan(),
                List.of(new PlaybookDraft.Criterion(
                        "state_conflict",
                        "状态冲突需要人工复核",
                        List.of("log_trace_bundle", "contrast_sample"),
                        List.of("SYNTH-TRACE-BUNDLE", "SYNTH-CONTRAST-SAMPLE"))),
                List.of(new PlaybookDraft.DiagnosisHypothesis(
                        "session_state_conflict",
                        "会话状态冲突",
                        List.of("SYNTH-TRACE-BUNDLE"))),
                List.of(new PlaybookDraft.HumanAction(
                        "verify_recovery",
                        "由值班研发在平台外验证恢复",
                        "EXTERNAL_HUMAN",
                        List.of("SYNTH-LOG-SEARCH"))),
                List.of(
                        "SYNTH-LOG-SEARCH",
                        "SYNTH-TRACE-BUNDLE",
                        "SYNTH-CONTRAST-SAMPLE"));
    }

    private PlaybookDraft.EvidencePlanStep step(String intent, String signal) {
        return new PlaybookDraft.EvidencePlanStep(intent, signal, "只读核实", true);
    }

    private ReferenceSolution reference() {
        return new ReferenceSolution(
                "eval-012345678901234567890123/reference/v1",
                "message_send_failed",
                List.of(
                        "locate_failed_request",
                        "trace_ps_id",
                        "compare_success_sample",
                        "verify_recovery"),
                List.of("restart_production"),
                List.of(
                        new ReferenceSolution.OrderingConstraint(
                                "locate_failed_request", "trace_ps_id"),
                        new ReferenceSolution.OrderingConstraint(
                                "trace_ps_id", "compare_success_sample"),
                        new ReferenceSolution.OrderingConstraint(
                                "compare_success_sample", "verify_recovery")),
                List.of("log_search", "log_trace_bundle", "contrast_sample"));
    }

    private ReferenceSolution referenceWithForbidden(String forbiddenIntent) {
        ReferenceSolution base = reference();
        return new ReferenceSolution(
                base.referenceId(),
                base.scenarioKey(),
                base.requiredStepIntents(),
                List.of(forbiddenIntent),
                base.orderingConstraints(),
                base.requiredEvidenceKinds());
    }

    private GuanceEvidenceSpineObservation observation(String anomaly) {
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
                                1, 42, "session-svc", "ERROR", anomaly, 42.0, true)),
                List.of(1),
                Map.of("session-svc", new LogTraceSkeleton.DurationSummary(1, 42, 42, 42)),
                2,
                0,
                new LogTraceSkeleton.ContrastSummary(
                        true, "state_conflict", 100, 92, 100, 3, 0.92, 0.03, 0.89));
        GuanceEvidenceSpinePreview preview = new GuanceEvidenceSpinePreview(
                GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED,
                new GuanceEvidenceReadiness(
                        "CSDP",
                        "session-svc",
                        GuanceEvidenceReadiness.Status.CANONICAL_SIGNALS_OBSERVED,
                        true,
                        true,
                        GuanceEvidenceReadiness.CredentialState.CONFIGURED,
                        true,
                        List.of(),
                        List.of()),
                4L,
                "ps-message-001",
                2,
                List.of("gateway", "session-svc"),
                1,
                42L,
                new GuanceEvidenceSpinePreview.Contrast(
                        true, 100, 92, 100, 3, 0.92, 0.03, 0.89),
                3,
                50L,
                List.of(
                        observed("log_search", "T8-GUANCE-LOG-SEARCH"),
                        observed("log_trace_bundle", "T8-GUANCE-TRACE-BUNDLE"),
                        observed("contrast_sample", "T8-GUANCE-CONTRAST-SAMPLE")),
                NOW,
                List.of());
        return new GuanceEvidenceSpineObservation(preview, skeleton);
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
        return new SopSynthesisPreview(
                SopSynthesisPreview.Stage.READY_FOR_MODEL,
                "CSDP",
                "session-svc",
                SEARCH_TERM,
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
                new EvidenceSpineTimings(10L, 20L, 5L, 5L),
                NOW,
                List.of());
    }

    private SopSynthesisPreview.EvidenceReference replayEvidence(String queryId) {
        return new SopSynthesisPreview.EvidenceReference(
                queryId, EvidenceStatus.ANOMALY, "recorded-replay", NOW);
    }

    private GuanceEvidenceSpinePreview.Step observed(String kind, String ref) {
        return new GuanceEvidenceSpinePreview.Step(
                kind,
                GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                ref,
                NOW);
    }

    private BaselineEvaluationRun existingRun(
            EvidenceEvaluationSample sample,
            String runKey) {
        return new BaselineEvaluationRun(
                "baseline-existing",
                runKey,
                sample.sampleId(),
                sample.diagnosisId(),
                sample.version(),
                sample.sourcePlatform(),
                sample.evidence().fixtureMode(),
                sample.diagnosisFixtureMode(),
                sample.modelInputHash(),
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
                        "openai", "fixed-model", "7:model-config-v1", NOW,
                        1, 320L, 160L, 480L),
                50,
                150,
                200,
                "reviewer",
                NOW);
    }

    private record Fixture(
            BaselineEvaluationRunService service,
            EvidenceEvaluationSample sample,
            GuanceEvidenceSpinePreviewService preview,
            GuanceEvidenceAcceptanceService acceptance,
            PlaybookDraftInducer inducer,
            BaselineEvaluationRunStore runStore) {
    }
}
