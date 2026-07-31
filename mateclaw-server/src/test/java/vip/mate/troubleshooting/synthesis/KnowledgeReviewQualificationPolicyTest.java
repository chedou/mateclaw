package vip.mate.troubleshooting.synthesis;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.ActionType;
import vip.mate.troubleshooting.model.ApprovalStatus;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.ExecutionStatus;
import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.RecommendedAction;
import vip.mate.troubleshooting.model.SopEntry;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeReviewQualificationPolicyTest {

    private final KnowledgeReviewQualificationPolicy policy =
            new KnowledgeReviewQualificationPolicy();

    @Test
    void evidenceQualificationUsesCurrentFactsInsteadOfTheLegacyP1Placeholder() {
        KnowledgeReviewSource source = policy.evidence(evidenceRecord());

        assertThat(source.snapshot().validationStatus()).isEqualTo("VALID");
        assertThat(source.snapshot().qualificationPhase())
                .isEqualTo(KnowledgeQualificationPhase.CALIBRATION);
        assertThat(source.snapshot().approvalEligibility()).isEqualTo("NOT_ELIGIBLE");
        assertThat(source.snapshot().eligibilityReasons()).containsExactly(
                "OWNER_REQUIRED",
                "POSITIVE_REPLAY_REQUIRED",
                "NEGATIVE_OR_ABSTAIN_REPLAY_REQUIRED",
                "FIXTURE_ONLY");
        assertThat(source.snapshot().eligibilityReasons())
                .doesNotContain("P1_CALIBRATION_PERIOD");
    }

    @Test
    void outcomeQualificationExposesOnlyTheConditionsTheSourceCanActuallyProve() {
        KnowledgeReviewSource source = policy.outcome(outcomeCandidate());

        assertThat(source.snapshot().validationStatus()).isEqualTo("NOT_EVALUATED");
        assertThat(source.snapshot().qualificationPhase())
                .isEqualTo(KnowledgeQualificationPhase.NOT_APPLICABLE);
        assertThat(source.snapshot().approvalEligibility()).isEqualTo("NOT_ELIGIBLE");
        assertThat(source.snapshot().eligibilityReasons()).containsExactly(
                "OUTCOME_VERIFICATION_NOT_PROJECTED",
                "POSITIVE_REPLAY_REQUIRED",
                "OWNER_REQUIRED");
    }

    @Test
    void manualQualificationRunsDeterministicContractChecksBeforeReplayGates() {
        KnowledgeReviewSource source = policy.manual(manualSop());

        assertThat(source.snapshot().validationStatus()).isEqualTo("VALID");
        assertThat(source.snapshot().qualificationPhase())
                .isEqualTo(KnowledgeQualificationPhase.NOT_APPLICABLE);
        assertThat(source.snapshot().validationErrors()).isEmpty();
        assertThat(source.snapshot().approvalEligibility()).isEqualTo("NOT_ELIGIBLE");
        assertThat(source.snapshot().eligibilityReasons())
                .containsExactly(
                        "POSITIVE_AND_NEGATIVE_REPLAY_REQUIRED");
    }

    @Test
    void manualQualificationAcceptsOnlyAPassedProofForTheExactCandidateAndSuite() {
        String candidateFingerprint = "a".repeat(64);
        String suiteFingerprint = "b".repeat(64);
        ManualPlaybookReplayAttestation attestation =
                new ManualPlaybookReplayAttestation(
                        "manual-replay-1",
                        "sop-1",
                        "csdp:903002",
                        candidateFingerprint,
                        "manual-suite/v1",
                        1,
                        suiteFingerprint,
                        ManualPlaybookReplayAttestation.Status.PASSED,
                        1,
                        1,
                        2,
                        2,
                        List.of(),
                        true,
                        "reviewer-a",
                        Instant.parse("2026-07-31T02:00:00Z"));

        KnowledgeReviewSource source = policy.manual(
                manualSop(),
                new ManualPlaybookReplayQualification(
                        candidateFingerprint, suiteFingerprint, attestation));

        assertThat(source.snapshot().approvalEligibility())
                .isEqualTo("ELIGIBLE_FOR_APPROVAL");
        assertThat(source.snapshot().eligibilityReasons()).isEmpty();
        assertThat(source.snapshot().manualReplay()).isEqualTo(attestation);
    }

    @Test
    void manualQualificationFailsClosedForAStaleReplayProof() {
        ManualPlaybookReplayAttestation stale =
                new ManualPlaybookReplayAttestation(
                        "manual-replay-1",
                        "sop-1",
                        "csdp:903002",
                        "c".repeat(64),
                        "manual-suite/v1",
                        1,
                        "d".repeat(64),
                        ManualPlaybookReplayAttestation.Status.PASSED,
                        1,
                        1,
                        1,
                        1,
                        List.of(),
                        true,
                        "reviewer-a",
                        Instant.parse("2026-07-31T02:00:00Z"));

        KnowledgeReviewSource source = policy.manual(
                manualSop(),
                new ManualPlaybookReplayQualification(
                        "a".repeat(64), "b".repeat(64), stale));

        assertThat(source.snapshot().approvalEligibility()).isEqualTo("NOT_ELIGIBLE");
        assertThat(source.snapshot().eligibilityReasons())
                .containsExactly("REPLAY_PROOF_STALE");
    }

    @Test
    void manualQualificationReportsBrokenCrossReferencesAndMissingOwner() {
        SopEntry invalid = new SopEntry(
                "sop-invalid", SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP", "903003", "session-svc", "无效候选", "",
                "network", null, "candidate", false,
                List.of(new EvidenceRequest(
                        "EV-1", "log_count", "确认错误", Map.of(), "-15m", true)),
                List.of(new AnomalyCriterion(
                        "error_present", "EV-MISSING", "错误出现",
                        new Criterion.NumericGte("count", 1))),
                List.of(new DiagnosisRule(
                        "R-1", List.of("unknown_signal"), "连接异常", "超时",
                        Confidence.HIGH, false)),
                List.of());

        KnowledgeReviewSource source = policy.manual(invalid);

        assertThat(source.snapshot().validationStatus()).isEqualTo("INVALID");
        assertThat(source.snapshot().validationErrors())
                .extracting(PlaybookDraft.ValidationError::code)
                .containsExactlyInAnyOrder(
                        "UNKNOWN_EVIDENCE_REQUEST",
                        "UNKNOWN_REQUIRED_SIGNAL");
        assertThat(source.snapshot().eligibilityReasons()).containsExactly(
                "CONTRACT_VALIDATION_FAILED",
                "OWNER_REQUIRED",
                "POSITIVE_AND_NEGATIVE_REPLAY_REQUIRED");
    }

    @Test
    void manualQualificationRejectsUnsupportedContractsAndSecretShapedContent() {
        SopEntry base = manualSop();
        SopEntry invalid = new SopEntry(
                base.sopId(), "sop.v0", base.system(), base.errorCode(),
                base.service(), "password=do-not-store", base.cause(), base.category(),
                base.ownerTeam(), base.status(), base.verified(), base.evidenceRequests(),
                base.anomalyCriteria(), base.diagnosisRules(), base.actions());

        KnowledgeReviewSource source = policy.manual(invalid);

        assertThat(source.snapshot().validationStatus()).isEqualTo("INVALID");
        assertThat(source.snapshot().validationErrors())
                .extracting(PlaybookDraft.ValidationError::code)
                .contains("UNSUPPORTED_CONTRACT_VERSION", "SECRET_NOT_REDACTED");
        assertThat(source.snapshot().approvalEligibility()).isEqualTo("NOT_ELIGIBLE");
    }

    @Test
    void manualQualificationRejectsEmbeddedGuanceDqlNamespaces() {
        SopEntry base = manualSop();
        SopEntry invalid = new SopEntry(
                base.sopId(), base.contractVersion(), base.system(), base.errorCode(),
                base.service(), base.title(), base.cause(), base.category(),
                base.ownerTeam(), base.status(), base.verified(),
                List.of(new EvidenceRequest(
                        "EV-1", "log_count", "D::http_dial_testing", Map.of(),
                        "-15m", true)),
                base.anomalyCriteria(), base.diagnosisRules(), base.actions());

        KnowledgeReviewSource source = policy.manual(invalid);

        assertThat(source.snapshot().validationErrors())
                .extracting(PlaybookDraft.ValidationError::code)
                .contains("DQL_OR_RAW_LOG_FORBIDDEN");
    }

    @Test
    void manualQualificationRejectsEvidenceWindowsBeyondTheExecutionBudget() {
        SopEntry base = manualSop();
        SopEntry invalid = new SopEntry(
                base.sopId(), base.contractVersion(), base.system(), base.errorCode(),
                base.service(), base.title(), base.cause(), base.category(),
                base.ownerTeam(), base.status(), base.verified(),
                List.of(new EvidenceRequest(
                        "EV-1", "log_count", "确认错误", Map.of(), "-25h", true)),
                base.anomalyCriteria(), base.diagnosisRules(), base.actions());

        KnowledgeReviewSource source = policy.manual(invalid);

        assertThat(source.snapshot().validationErrors())
                .extracting(PlaybookDraft.ValidationError::code)
                .contains("INVALID_WINDOW");
        assertThat(source.snapshot().approvalEligibility()).isEqualTo("NOT_ELIGIBLE");
    }

    @Test
    void manualQualificationRejectsPreExecutedOrMistypedActions() {
        SopEntry base = manualSop();
        SopEntry invalid = new SopEntry(
                base.sopId(), base.contractVersion(), base.system(), base.errorCode(),
                base.service(), base.title(), base.cause(), base.category(),
                base.ownerTeam(), base.status(), base.verified(), base.evidenceRequests(),
                base.anomalyCriteria(), base.diagnosisRules(),
                List.of(new RecommendedAction(
                        "ACTION-1", ActionType.AUTO_READONLY, "重启生产服务", "curl endpoint",
                        false, ApprovalStatus.NOT_REQUIRED, ExecutionStatus.COMPLETED)));

        KnowledgeReviewSource source = policy.manual(invalid);

        assertThat(source.snapshot().validationErrors())
                .extracting(PlaybookDraft.ValidationError::code)
                .contains(
                        "NON_WRITE_ACTION_STATE_INVALID",
                        "PRODUCTION_WRITE_FORBIDDEN",
                        "TOOL_CALL_FORBIDDEN");
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
                NorthStarTimings.concluded(
                        now.minusSeconds(10), now.minusSeconds(5), now),
                now);
    }

    private KnowledgeCandidate outcomeCandidate() {
        return new KnowledgeCandidate(
                "candidate-1", KnowledgeCandidate.LEGACY_CONTRACT_VERSION,
                "diag-1", "case-1", "run-1", "CSDP", "903001",
                "csdp:903001", "连接池耗尽", List.of("LOG-1"),
                List.of(), List.of(), "人工扩容后恢复", null, "closer-a",
                Instant.parse("2026-07-29T10:00:00Z"));
    }

    @Test
    void outcomeQualificationCreditsOnlyTheFrozenServerOwnedClosureProof() {
        Instant closedAt = Instant.parse("2026-07-29T10:00:00Z");
        KnowledgeCandidate candidate = new KnowledgeCandidate(
                "candidate-2", KnowledgeCandidate.CURRENT_CONTRACT_VERSION,
                "diag-2", "case-2", "run-2", "CSDP", "903001",
                "csdp:903001", "连接池耗尽", List.of("LOG-1"),
                List.of(), List.of(), "人工扩容后恢复", null, "closer-a",
                closedAt,
                new KnowledgeCandidate.OutcomeProof(
                        vip.mate.troubleshooting.model.ClosureOutcome.RECOVERED,
                        true,
                        "closer-a",
                        closedAt),
                "订单平台组");

        KnowledgeReviewSource source = policy.outcome(candidate);

        assertThat(source.snapshot().eligibilityReasons())
                .doesNotContain("OUTCOME_VERIFICATION_NOT_PROJECTED")
                .containsExactly("POSITIVE_REPLAY_REQUIRED");
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
