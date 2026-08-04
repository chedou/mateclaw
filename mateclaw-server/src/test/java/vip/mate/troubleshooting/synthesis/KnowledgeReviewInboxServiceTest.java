package vip.mate.troubleshooting.synthesis;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.service.SopRegistryRecord;
import vip.mate.troubleshooting.service.SopSummary;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeReviewInboxServiceTest {

    @Test
    void returnsCurrentServerQualificationForEveryExactInboxSource() {
        PlaybookCandidateReader evidence = mock(PlaybookCandidateReader.class);
        TroubleshootingPersistenceService outcomes =
                mock(TroubleshootingPersistenceService.class);
        TroubleshootingSopPersistenceService manual =
                mock(TroubleshootingSopPersistenceService.class);
        KnowledgeReviewWorkflowService reviews =
                mock(KnowledgeReviewWorkflowService.class);
        ManualPlaybookReplayService replays = mock(ManualPlaybookReplayService.class);
        KnowledgeCandidate outcome = outcomeCandidate();
        SopEntry manualEntry = manualSop();
        SopSummary manualSummary = new SopSummary(
                manualEntry.sopId(),
                manualEntry.routingKey(),
                manualEntry.system(),
                manualEntry.errorCode(),
                manualEntry.service(),
                manualEntry.status(),
                manualEntry.verified(),
                manualEntry.operational(),
                LocalDateTime.parse("2026-07-29T10:00:00"),
                LocalDateTime.parse("2026-07-29T10:00:00"));
        when(evidence.list(7L, 25)).thenReturn(List.of());
        when(outcomes.listKnowledgeCandidates(7L, 25))
                .thenReturn(List.of(outcome));
        when(manual.listRecords(7L, "candidate", null, 25))
                .thenReturn(List.of(new SopRegistryRecord(
                        manualSummary, manualEntry)));
        when(reviews.listForSources(eq(7L), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of());
        ManualPlaybookReplayQualification replay = passedReplay(manualEntry);
        when(replays.qualification(7L, manualEntry)).thenReturn(replay);
        KnowledgeReviewInboxService service = new KnowledgeReviewInboxService(
                evidence, outcomes, manual, reviews, replays);

        KnowledgeReviewInbox inbox = service.read(7L, 25);

        assertThat(inbox.outcomeBacked()).containsExactly(outcome);
        assertThat(inbox.manual()).containsExactly(manualSummary);
        assertThat(inbox.sourceStates())
                .extracting(KnowledgeReviewSource::origin)
                .containsExactly(
                        KnowledgeOrigin.OUTCOME_BACKED,
                        KnowledgeOrigin.MANUAL);
        assertThat(inbox.sourceStates().getFirst().snapshot().eligibilityReasons())
                .containsExactly(
                        "OUTCOME_VERIFICATION_NOT_PROJECTED",
                        "NO_ROUTEABLE_PLAYBOOK_PROJECTED",
                        "OWNER_REQUIRED");
        assertThat(inbox.sourceStates().getLast().snapshot().validationStatus())
                .isEqualTo("VALID");
        assertThat(inbox.sourceStates().getLast().snapshot().approvalEligibility())
                .isEqualTo("ELIGIBLE_FOR_APPROVAL");
        assertThat(inbox.sourceStates().getLast().snapshot().manualReplay())
                .isEqualTo(replay.attestation());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeReviewSourceKey>> keys =
                ArgumentCaptor.forClass(List.class);
        verify(reviews).listForSources(eq(7L), keys.capture());
        assertThat(keys.getValue()).containsExactly(
                new KnowledgeReviewSourceKey(
                        KnowledgeOrigin.OUTCOME_BACKED, outcome.candidateId()),
                new KnowledgeReviewSourceKey(
                        KnowledgeOrigin.MANUAL, manualEntry.sopId()));
    }

    private KnowledgeCandidate outcomeCandidate() {
        return new KnowledgeCandidate(
                "candidate-1", KnowledgeCandidate.LEGACY_CONTRACT_VERSION,
                "diag-1", "case-1", "run-1", "CSDP", "903001",
                "csdp:903001", "连接池耗尽", List.of("LOG-1"),
                List.of(), List.of(), "人工扩容后恢复", null, "closer-a",
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

    private ManualPlaybookReplayQualification passedReplay(SopEntry sop) {
        String candidateFingerprint = "a".repeat(64);
        String suiteFingerprint = "b".repeat(64);
        return new ManualPlaybookReplayQualification(
                candidateFingerprint,
                suiteFingerprint,
                new ManualPlaybookReplayAttestation(
                        "replay-1", sop.sopId(), sop.routingKey(),
                        candidateFingerprint, "manual-suite/v1", 1,
                        suiteFingerprint,
                        ManualPlaybookReplayAttestation.Status.PASSED,
                        1, 1, 1, 1, List.of(), true, "reviewer-a",
                        Instant.parse("2026-07-31T03:00:00Z")));
    }
}
