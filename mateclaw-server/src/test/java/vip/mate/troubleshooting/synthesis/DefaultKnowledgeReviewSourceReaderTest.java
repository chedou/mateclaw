package vip.mate.troubleshooting.synthesis;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultKnowledgeReviewSourceReaderTest {

    @Test
    void freezesEvidenceValidationReferenceAndModelFacts() {
        PlaybookCandidateReader evidence = mock(PlaybookCandidateReader.class);
        TroubleshootingPersistenceService outcomes =
                mock(TroubleshootingPersistenceService.class);
        TroubleshootingSopPersistenceService manual =
                mock(TroubleshootingSopPersistenceService.class);
        when(evidence.find(7L, "record-1")).thenReturn(evidenceRecord());
        DefaultKnowledgeReviewSourceReader reader =
                new DefaultKnowledgeReviewSourceReader(evidence, outcomes, manual);

        KnowledgeReviewSource source = reader.find(
                7L, KnowledgeOrigin.EVIDENCE_DERIVED, "record-1").orElseThrow();

        assertThat(source.selectorKey())
                .isEqualTo("csdp:scenario:message_send_failed");
        assertThat(source.snapshot().validationStatus()).isEqualTo("VALID");
        assertThat(source.snapshot().referenceComparison().referenceId())
                .isEqualTo("reference-message-send/v1");
        assertThat(source.snapshot().modelConfigVersion()).isEqualTo("7:v1");
        assertThat(source.snapshot().fixtureMode()).isTrue();
        assertThat(source.snapshot().eligibilityReasons())
                .containsExactly(
                        "OWNER_REQUIRED",
                        "POSITIVE_REPLAY_REQUIRED",
                        "NEGATIVE_OR_ABSTAIN_REPLAY_REQUIRED",
                        "FIXTURE_ONLY");
        verify(evidence).find(7L, "record-1");
    }

    @Test
    void givesOutcomeBackedCandidatesAnHonestNotEvaluatedSnapshot() {
        PlaybookCandidateReader evidence = mock(PlaybookCandidateReader.class);
        TroubleshootingPersistenceService outcomes =
                mock(TroubleshootingPersistenceService.class);
        TroubleshootingSopPersistenceService manual =
                mock(TroubleshootingSopPersistenceService.class);
        when(outcomes.findKnowledgeCandidate(7L, "candidate-1"))
                .thenReturn(outcomeCandidate());
        DefaultKnowledgeReviewSourceReader reader =
                new DefaultKnowledgeReviewSourceReader(evidence, outcomes, manual);

        KnowledgeReviewSource source = reader.find(
                7L, KnowledgeOrigin.OUTCOME_BACKED, "candidate-1").orElseThrow();

        assertThat(source.selectorKey()).isEqualTo("csdp:903001");
        assertThat(source.snapshot().validationStatus()).isEqualTo("NOT_EVALUATED");
        assertThat(source.snapshot().referenceComparison()).isNull();
        assertThat(source.snapshot().modelConfigVersion()).isNull();
        assertThat(source.snapshot().fixtureMode()).isNull();
        assertThat(source.snapshot().approvalEligibility()).isEqualTo("NOT_ELIGIBLE");
        assertThat(source.snapshot().eligibilityReasons())
                .containsExactly(
                        "OUTCOME_VERIFICATION_NOT_PROJECTED",
                        "POSITIVE_REPLAY_REQUIRED",
                        "OWNER_REQUIRED");
    }

    @Test
    void givesManualCandidatesAnHonestNotEvaluatedSnapshot() {
        PlaybookCandidateReader evidence = mock(PlaybookCandidateReader.class);
        TroubleshootingPersistenceService outcomes =
                mock(TroubleshootingPersistenceService.class);
        TroubleshootingSopPersistenceService manual =
                mock(TroubleshootingSopPersistenceService.class);
        when(manual.findBySopId(7L, "sop-1")).thenReturn(manualSop());
        DefaultKnowledgeReviewSourceReader reader =
                new DefaultKnowledgeReviewSourceReader(evidence, outcomes, manual);

        KnowledgeReviewSource source = reader.find(
                7L, KnowledgeOrigin.MANUAL, "sop-1").orElseThrow();

        assertThat(source.selectorKey()).isEqualTo("csdp:903002");
        assertThat(source.snapshot().validationStatus()).isEqualTo("VALID");
        assertThat(source.snapshot().eligibilityReasons())
                .containsExactly("POSITIVE_AND_NEGATIVE_REPLAY_REQUIRED");
    }

    @Test
    void returnsEmptyInsteadOfCrossingWorkspaceBoundaries() {
        PlaybookCandidateReader evidence = mock(PlaybookCandidateReader.class);
        TroubleshootingPersistenceService outcomes =
                mock(TroubleshootingPersistenceService.class);
        TroubleshootingSopPersistenceService manual =
                mock(TroubleshootingSopPersistenceService.class);
        when(evidence.find(8L, "record-1")).thenReturn(null);
        DefaultKnowledgeReviewSourceReader reader =
                new DefaultKnowledgeReviewSourceReader(evidence, outcomes, manual);

        assertThat(reader.find(
                8L, KnowledgeOrigin.EVIDENCE_DERIVED, "record-1")).isEmpty();
    }

    private PlaybookKnowledgeRecord evidenceRecord() {
        Instant now = Instant.parse("2026-07-29T10:00:00Z");
        PlaybookDraft draft = new PlaybookDraft(
                "draft-1", "a".repeat(64), "incident-1", "SCENARIO",
                new PlaybookDraft.ProposedSelector(
                        "CSDP", "message_send_failed", null),
                "消息发送失败排查草稿",
                List.of(), List.of(), List.of(), List.of(),
                List.of("SYNTH-LOG-SEARCH"),
                new PlaybookDraft.ModelProvenance(
                        "openai", "fixed", "7:v1",
                        PlaybookDraft.CONTRACT_VERSION, now, 1),
                false,
                List.of());
        return new PlaybookKnowledgeRecord(
                "record-1", draft, "EVIDENCE_DERIVED", "CANDIDATE", "VALID",
                "", "", "bundle-1", "session-svc",
                new ReferenceSolutionComparator.Comparison(
                        "reference-message-send/v1", true, 1.0,
                        List.of(), List.of(), List.of(), List.of()),
                "NOT_ELIGIBLE", List.of("P1_CALIBRATION_PERIOD"), true,
                NorthStarTimings.concluded(now.minusSeconds(10), now.minusSeconds(5), now),
                now);
    }

    private KnowledgeCandidate outcomeCandidate() {
        return new KnowledgeCandidate(
                "candidate-1", KnowledgeCandidate.CURRENT_CONTRACT_VERSION,
                "diag-1", "case-1", "run-1", "CSDP", "903001",
                "csdp:903001", "连接池耗尽", List.of("LOG-1"),
                List.of(), List.of(), "人工扩容后恢复", null, "owner-a",
                Instant.parse("2026-07-29T10:00:00Z"));
    }

    private SopEntry manualSop() {
        return new SopEntry(
                "sop-1", SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP", "903002", "session-svc", "会话超时", "连接异常",
                "network", "会话组", "candidate", false,
                List.of(new EvidenceRequest(
                        "EV-1", "log_count", "确认错误", Map.of(), "-15m", true)),
                List.of(new AnomalyCriterion(
                        "error_present", "EV-1", "错误出现",
                        new Criterion.NumericGte("count", 1))),
                List.of(new DiagnosisRule(
                        "R-1", List.of("error_present"), "连接异常", "超时",
                        Confidence.HIGH, false)),
                List.of());
    }
}
