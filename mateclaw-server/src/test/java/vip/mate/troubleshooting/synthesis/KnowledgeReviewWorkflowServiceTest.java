package vip.mate.troubleshooting.synthesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.model.TroubleshootingKnowledgeReviewEntity;
import vip.mate.troubleshooting.repository.TroubleshootingKnowledgeReviewMapper;
import vip.mate.troubleshooting.service.TroubleshootingPlaybookVersionService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeReviewWorkflowServiceTest {

    @Test
    void startsAWorkspaceScopedReviewWithAnAuditableSourceSnapshot() {
        TroubleshootingKnowledgeReviewMapper mapper =
                mock(TroubleshootingKnowledgeReviewMapper.class);
        KnowledgeReviewSourceReader sources = mock(KnowledgeReviewSourceReader.class);
        KnowledgeReviewWorkflowService service = service(mapper, sources);
        when(mapper.findBySource(7L, "EVIDENCE_DERIVED", "record-1"))
                .thenReturn(null);
        when(sources.find(7L, KnowledgeOrigin.EVIDENCE_DERIVED, "record-1"))
                .thenReturn(Optional.of(source()));
        when(mapper.insert(any(TroubleshootingKnowledgeReviewEntity.class)))
                .thenReturn(1);

        KnowledgeReviewState state = service.start(
                7L,
                KnowledgeOrigin.EVIDENCE_DERIVED,
                "record-1",
                0,
                "reviewer-a",
                "核对固定回放与引用");

        assertThat(state.status()).isEqualTo(KnowledgeReviewStatus.IN_REVIEW);
        assertThat(state.version()).isEqualTo(1);
        assertThat(state.reviewer()).isEqualTo("reviewer-a");
        assertThat(state.reason()).isEqualTo("核对固定回放与引用");
        assertThat(state.selectorKey()).isEqualTo("csdp:scenario:message_send_failed");
        assertThat(state.snapshot().validationStatus()).isEqualTo("VALID");
        assertThat(state.snapshot().modelConfigVersion()).isEqualTo("model-config-v7");
        assertThat(state.snapshot().referenceComparison().referenceId())
                .isEqualTo("reference-1");
        assertThat(state.snapshot().fixtureMode()).isTrue();

        ArgumentCaptor<TroubleshootingKnowledgeReviewEntity> inserted =
                ArgumentCaptor.forClass(TroubleshootingKnowledgeReviewEntity.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getWorkspaceId()).isEqualTo(7L);
        assertThat(inserted.getValue().getOrigin()).isEqualTo("EVIDENCE_DERIVED");
        assertThat(inserted.getValue().getSourceRecordId()).isEqualTo("record-1");
        assertThat(inserted.getValue().getStatus()).isEqualTo("IN_REVIEW");
        assertThat(inserted.getValue().getVersion()).isEqualTo(1);
        assertThat(inserted.getValue().getActiveBaselineKnown()).isTrue();
        assertThat(inserted.getValue().getBasePlaybookId()).isNull();
        assertThat(inserted.getValue().getBasePlaybookVersion()).isNull();
        assertThat(inserted.getValue().getSnapshotJson())
                .contains("model-config-v7")
                .doesNotContain("searchTerm", "rawLog", "credential");
    }

    @Test
    void refusesToStartAReviewWhenTheSourceDoesNotExistInTheWorkspace() {
        TroubleshootingKnowledgeReviewMapper mapper =
                mock(TroubleshootingKnowledgeReviewMapper.class);
        KnowledgeReviewSourceReader sources = mock(KnowledgeReviewSourceReader.class);
        KnowledgeReviewWorkflowService service = service(mapper, sources);
        when(mapper.findBySource(8L, "OUTCOME_BACKED", "candidate-1"))
                .thenReturn(null);
        when(sources.find(8L, KnowledgeOrigin.OUTCOME_BACKED, "candidate-1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(
                8L,
                KnowledgeOrigin.OUTCOME_BACKED,
                "candidate-1",
                0,
                "reviewer-a",
                "核对关闭结果"))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(404);
        verify(mapper, never()).insert(
                any(TroubleshootingKnowledgeReviewEntity.class));
    }

    @Test
    void refusesCredentialsOrRawDeveloperEvidenceInThePersistedReviewReason() {
        TroubleshootingKnowledgeReviewMapper mapper =
                mock(TroubleshootingKnowledgeReviewMapper.class);
        KnowledgeReviewSourceReader sources = mock(KnowledgeReviewSourceReader.class);
        KnowledgeReviewWorkflowService service = service(mapper, sources);

        assertThatThrownBy(() -> service.start(
                7L,
                KnowledgeOrigin.EVIDENCE_DERIVED,
                "record-1",
                0,
                "reviewer-a",
                "password=super-secret"))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(400);
        assertThatThrownBy(() -> service.start(
                7L,
                KnowledgeOrigin.EVIDENCE_DERIVED,
                "record-1",
                0,
                "reviewer-a",
                "请粘贴原始日志后再审核"))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(400);
        verify(mapper, never()).insert(
                any(TroubleshootingKnowledgeReviewEntity.class));
    }

    @Test
    void rejectsOnlyTheExactInReviewVersionAndAdvancesTheOptimisticVersion() {
        TroubleshootingKnowledgeReviewMapper mapper =
                mock(TroubleshootingKnowledgeReviewMapper.class);
        KnowledgeReviewSourceReader sources = mock(KnowledgeReviewSourceReader.class);
        KnowledgeReviewWorkflowService service = service(mapper, sources);
        TroubleshootingKnowledgeReviewEntity current = persisted("IN_REVIEW", 1);
        when(mapper.findBySource(7L, "EVIDENCE_DERIVED", "record-1"))
                .thenReturn(current);
        when(mapper.transition(
                eq(7L),
                eq("review-1"),
                eq("IN_REVIEW"),
                eq("REJECTED"),
                eq(1),
                eq("reviewer-b"),
                eq("缺少负例回放"),
                any(LocalDateTime.class)))
                .thenReturn(1);

        KnowledgeReviewState rejected = service.reject(
                7L,
                KnowledgeOrigin.EVIDENCE_DERIVED,
                "record-1",
                1,
                "reviewer-b",
                "缺少负例回放");

        assertThat(rejected.status()).isEqualTo(KnowledgeReviewStatus.REJECTED);
        assertThat(rejected.version()).isEqualTo(2);
        assertThat(rejected.reviewer()).isEqualTo("reviewer-b");
        assertThat(rejected.reason()).isEqualTo("缺少负例回放");
    }

    @Test
    void staleReviewDecisionFailsClosedWithoutUpdatingTheLedger() {
        TroubleshootingKnowledgeReviewMapper mapper =
                mock(TroubleshootingKnowledgeReviewMapper.class);
        KnowledgeReviewSourceReader sources = mock(KnowledgeReviewSourceReader.class);
        KnowledgeReviewWorkflowService service = service(mapper, sources);
        when(mapper.findBySource(7L, "MANUAL", "sop-1"))
                .thenReturn(persisted("IN_REVIEW", 2));

        assertThatThrownBy(() -> service.reject(
                7L,
                KnowledgeOrigin.MANUAL,
                "sop-1",
                1,
                "reviewer-a",
                "合同回放不完整"))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);
        verify(mapper, never()).transition(
                anyLong(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void listsReviewStatesForTheExactInboxSourcesInsteadOfARecentGlobalSlice() {
        TroubleshootingKnowledgeReviewMapper mapper =
                mock(TroubleshootingKnowledgeReviewMapper.class);
        KnowledgeReviewSourceReader sources = mock(KnowledgeReviewSourceReader.class);
        KnowledgeReviewWorkflowService service = service(mapper, sources);
        TroubleshootingKnowledgeReviewEntity outcome = persisted("IN_REVIEW", 1);
        outcome.setOrigin("OUTCOME_BACKED");
        outcome.setSourceRecordId("candidate-1");
        when(mapper.listBySources(eq(7L), any()))
                .thenReturn(List.of(outcome));
        List<KnowledgeReviewSourceKey> requested = List.of(
                new KnowledgeReviewSourceKey(
                        KnowledgeOrigin.EVIDENCE_DERIVED, "record-1"),
                new KnowledgeReviewSourceKey(
                        KnowledgeOrigin.OUTCOME_BACKED, "candidate-1"),
                new KnowledgeReviewSourceKey(
                        KnowledgeOrigin.MANUAL, "sop-1"));

        List<KnowledgeReviewState> states = service.listForSources(7L, requested);

        assertThat(states).extracting(KnowledgeReviewState::sourceRecordId)
                .containsExactly("candidate-1");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeReviewSourceKey>> keys =
                ArgumentCaptor.forClass(List.class);
        verify(mapper).listBySources(eq(7L), keys.capture());
        assertThat(keys.getValue()).containsExactlyElementsOf(requested);
    }

    @Test
    void readsPrePhaseSnapshotsAsUnknownInsteadOfBreakingTheReviewLedger() {
        TroubleshootingKnowledgeReviewMapper mapper =
                mock(TroubleshootingKnowledgeReviewMapper.class);
        TroubleshootingKnowledgeReviewEntity legacy = persisted("IN_REVIEW", 1);
        legacy.setSnapshotJson("""
                {
                  "validationStatus":"VALID",
                  "validationErrors":[],
                  "referenceComparison":null,
                  "modelConfigVersion":"model-config-v7",
                  "approvalEligibility":"NOT_ELIGIBLE",
                  "eligibilityReasons":["FIXTURE_ONLY"],
                  "fixtureMode":true
                }
                """);
        when(mapper.listBySources(eq(7L), any()))
                .thenReturn(List.of(legacy));

        KnowledgeReviewState state = service(
                mapper, mock(KnowledgeReviewSourceReader.class))
                .listForSources(
                        7L,
                        List.of(new KnowledgeReviewSourceKey(
                                KnowledgeOrigin.EVIDENCE_DERIVED, "record-1")))
                .getFirst();

        assertThat(state.snapshot().qualificationPhase())
                .isEqualTo(KnowledgeQualificationPhase.UNKNOWN);
    }

    @Test
    void approvalRechecksCurrentEligibilityAndCreatesANewPlaybookVersion() {
        TroubleshootingKnowledgeReviewMapper mapper =
                mock(TroubleshootingKnowledgeReviewMapper.class);
        KnowledgeReviewSourceReader sources = mock(KnowledgeReviewSourceReader.class);
        KnowledgePromotionMaterialReader materials =
                mock(KnowledgePromotionMaterialReader.class);
        TroubleshootingPlaybookVersionService versions =
                mock(TroubleshootingPlaybookVersionService.class);
        TroubleshootingKnowledgeReviewEntity current = persisted("IN_REVIEW", 1);
        current.setSelectorKey("csdp:903001");
        current.setActiveBaselineKnown(true);
        current.setBasePlaybookId("playbook-old");
        current.setBasePlaybookVersion(3);
        when(mapper.findBySource(7L, "EVIDENCE_DERIVED", "record-1"))
                .thenReturn(current);
        KnowledgeReviewSource eligible = eligibleSource();
        when(sources.find(7L, KnowledgeOrigin.EVIDENCE_DERIVED, "record-1"))
                .thenReturn(Optional.of(eligible));
        KnowledgePromotionMaterial material = new KnowledgePromotionMaterial(
                KnowledgeOrigin.EVIDENCE_DERIVED,
                "record-1",
                "csdp:903001",
                candidateSop());
        when(materials.find(7L, KnowledgeOrigin.EVIDENCE_DERIVED, "record-1"))
                .thenReturn(Optional.of(material));
        ApprovedPlaybookVersion promoted = new ApprovedPlaybookVersion(
                "playbook-new", 4, "csdp:903001", "APPROVED",
                "EVIDENCE_DERIVED", "record-1", "review-1", 1,
                "reviewer-b", "固定回放与 owner 证明完整", eligible.snapshot(),
                approvedSop("playbook-new"),
                java.time.Instant.parse("2026-07-29T10:05:00Z"),
                java.time.Instant.parse("2026-07-29T10:05:00Z"));
        when(versions.promote(
                eq(7L), eq(material), eq("review-1"), eq(1), eq(true),
                eq("playbook-old"), eq(3), eq("reviewer-b"),
                eq("固定回放与 owner 证明完整"), eq(eligible.snapshot())))
                .thenReturn(promoted);
        when(mapper.transition(
                eq(7L), eq("review-1"), eq("IN_REVIEW"), eq("APPROVED"),
                eq(1), eq("reviewer-b"), eq("固定回放与 owner 证明完整"),
                any(LocalDateTime.class)))
                .thenReturn(1);

        KnowledgeReviewApproval approval = service(
                mapper, sources, materials, versions).approve(
                7L,
                KnowledgeOrigin.EVIDENCE_DERIVED,
                "record-1",
                1,
                "reviewer-b",
                "固定回放与 owner 证明完整");

        assertThat(approval.review().status())
                .isEqualTo(KnowledgeReviewStatus.APPROVED);
        assertThat(approval.review().version()).isEqualTo(2);
        assertThat(approval.approvedVersion()).isEqualTo(promoted);
        verify(sources).find(7L, KnowledgeOrigin.EVIDENCE_DERIVED, "record-1");
        verify(materials).find(7L, KnowledgeOrigin.EVIDENCE_DERIVED, "record-1");
    }

    @Test
    void replacementApprovalDeprecatesThePriorReviewInTheSameWorkflow() {
        TroubleshootingKnowledgeReviewMapper mapper =
                mock(TroubleshootingKnowledgeReviewMapper.class);
        KnowledgeReviewSourceReader sources = mock(KnowledgeReviewSourceReader.class);
        KnowledgePromotionMaterialReader materials =
                mock(KnowledgePromotionMaterialReader.class);
        TroubleshootingPlaybookVersionService versions =
                mock(TroubleshootingPlaybookVersionService.class);
        TroubleshootingKnowledgeReviewEntity current = persisted("IN_REVIEW", 1);
        current.setSelectorKey("csdp:903001");
        current.setActiveBaselineKnown(true);
        current.setBasePlaybookId("playbook-old");
        current.setBasePlaybookVersion(3);
        when(mapper.findBySource(7L, "EVIDENCE_DERIVED", "record-1"))
                .thenReturn(current);
        KnowledgeReviewSource eligible = eligibleSource();
        when(sources.find(7L, KnowledgeOrigin.EVIDENCE_DERIVED, "record-1"))
                .thenReturn(Optional.of(eligible));
        KnowledgePromotionMaterial material = new KnowledgePromotionMaterial(
                KnowledgeOrigin.EVIDENCE_DERIVED,
                "record-1",
                "csdp:903001",
                candidateSop());
        when(materials.find(7L, KnowledgeOrigin.EVIDENCE_DERIVED, "record-1"))
                .thenReturn(Optional.of(material));
        ApprovedPlaybookVersion prior = new ApprovedPlaybookVersion(
                "playbook-old", 3, "csdp:903001", "APPROVED",
                "MANUAL", "sop-old", "review-old", 1,
                "reviewer-a", "首版资格通过", eligible.snapshot(),
                approvedSop("playbook-old"),
                java.time.Instant.parse("2026-07-29T09:00:00Z"),
                java.time.Instant.parse("2026-07-29T09:00:00Z"));
        ApprovedPlaybookVersion promoted = new ApprovedPlaybookVersion(
                "playbook-new", 4, "csdp:903001", "APPROVED",
                "EVIDENCE_DERIVED", "record-1", "review-1", 1,
                "reviewer-b", "替代版本回放通过", eligible.snapshot(),
                approvedSop("playbook-new"),
                java.time.Instant.parse("2026-07-29T10:05:00Z"),
                java.time.Instant.parse("2026-07-29T10:05:00Z"));
        when(versions.findCurrent(7L, "csdp:903001"))
                .thenReturn(Optional.of(prior));
        when(versions.promote(
                eq(7L), eq(material), eq("review-1"), eq(1), eq(true),
                eq("playbook-old"), eq(3), eq("reviewer-b"),
                eq("替代版本回放通过"), eq(eligible.snapshot())))
                .thenReturn(promoted);
        when(mapper.transition(
                eq(7L), eq("review-old"), eq("APPROVED"), eq("DEPRECATED"),
                eq(2), eq("reviewer-b"),
                eq("superseded by approved review review-1"),
                any(LocalDateTime.class)))
                .thenReturn(1);
        when(mapper.transition(
                eq(7L), eq("review-1"), eq("IN_REVIEW"), eq("APPROVED"),
                eq(1), eq("reviewer-b"), eq("替代版本回放通过"),
                any(LocalDateTime.class)))
                .thenReturn(1);

        KnowledgeReviewApproval approval = service(
                mapper, sources, materials, versions).approve(
                7L,
                KnowledgeOrigin.EVIDENCE_DERIVED,
                "record-1",
                1,
                "reviewer-b",
                "替代版本回放通过");

        assertThat(approval.approvedVersion()).isEqualTo(promoted);
        verify(mapper).transition(
                eq(7L), eq("review-old"), eq("APPROVED"), eq("DEPRECATED"),
                eq(2), eq("reviewer-b"),
                eq("superseded by approved review review-1"),
                any(LocalDateTime.class));
    }

    @Test
    void ineligibleCurrentSourceCannotBeApprovedEvenWhenTheFrozenReviewWasStarted() {
        TroubleshootingKnowledgeReviewMapper mapper =
                mock(TroubleshootingKnowledgeReviewMapper.class);
        KnowledgeReviewSourceReader sources = mock(KnowledgeReviewSourceReader.class);
        KnowledgePromotionMaterialReader materials =
                mock(KnowledgePromotionMaterialReader.class);
        TroubleshootingPlaybookVersionService versions =
                mock(TroubleshootingPlaybookVersionService.class);
        TroubleshootingKnowledgeReviewEntity current = persisted("IN_REVIEW", 1);
        current.setActiveBaselineKnown(true);
        when(mapper.findBySource(7L, "EVIDENCE_DERIVED", "record-1"))
                .thenReturn(current);
        when(sources.find(7L, KnowledgeOrigin.EVIDENCE_DERIVED, "record-1"))
                .thenReturn(Optional.of(source()));

        assertThatThrownBy(() -> service(
                mapper, sources, materials, versions).approve(
                7L,
                KnowledgeOrigin.EVIDENCE_DERIVED,
                "record-1",
                1,
                "reviewer-b",
                "尝试批准"))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verify(materials, never()).find(anyLong(), any(), anyString());
        verify(versions, never()).promote(
                anyLong(), any(), anyString(), anyInt(), anyBoolean(),
                any(), any(), anyString(), anyString(), any());
        verify(mapper, never()).transition(
                anyLong(), anyString(), anyString(), anyString(),
                anyInt(), anyString(), anyString(), any(LocalDateTime.class));
    }

    @Test
    void deprecationRetiresTheExactApprovedReviewAndItsActiveVersion() {
        TroubleshootingKnowledgeReviewMapper mapper =
                mock(TroubleshootingKnowledgeReviewMapper.class);
        KnowledgeReviewSourceReader sources = mock(KnowledgeReviewSourceReader.class);
        KnowledgePromotionMaterialReader materials =
                mock(KnowledgePromotionMaterialReader.class);
        TroubleshootingPlaybookVersionService versions =
                mock(TroubleshootingPlaybookVersionService.class);
        TroubleshootingKnowledgeReviewEntity current = persisted("APPROVED", 2);
        when(mapper.findBySource(7L, "EVIDENCE_DERIVED", "record-1"))
                .thenReturn(current);
        ApprovedPlaybookVersion retired = new ApprovedPlaybookVersion(
                "playbook-1", 1, "csdp:scenario:message_send_failed", "DEPRECATED",
                "EVIDENCE_DERIVED", "record-1", "review-1", 1,
                "reviewer-b", "资格通过", source().snapshot(),
                approvedSop("playbook-1"),
                java.time.Instant.parse("2026-07-29T10:00:00Z"),
                java.time.Instant.parse("2026-07-29T10:10:00Z"));
        when(versions.deprecateByReview(
                7L, "review-1", "reviewer-c", "规则已被新故障样本否定"))
                .thenReturn(retired);
        when(mapper.transition(
                eq(7L), eq("review-1"), eq("APPROVED"), eq("DEPRECATED"),
                eq(2), eq("reviewer-c"), eq("规则已被新故障样本否定"),
                any(LocalDateTime.class)))
                .thenReturn(1);

        KnowledgeReviewDeprecation result = service(
                mapper, sources, materials, versions).deprecate(
                7L,
                KnowledgeOrigin.EVIDENCE_DERIVED,
                "record-1",
                2,
                "reviewer-c",
                "规则已被新故障样本否定");

        assertThat(result.review().status())
                .isEqualTo(KnowledgeReviewStatus.DEPRECATED);
        assertThat(result.review().version()).isEqualTo(3);
        assertThat(result.deprecatedVersion()).isEqualTo(retired);
    }

    @Test
    void legacyRetirementKeepsActorAndReasonServerAudited() {
        TroubleshootingPlaybookVersionService versions =
                mock(TroubleshootingPlaybookVersionService.class);
        ApprovedPlaybookVersion retired = new ApprovedPlaybookVersion(
                "legacy-approved", 1, "csdp:903001", "DEPRECATED",
                "LEGACY", "legacy-approved", null, null,
                "legacy-migration", "V186 backfill", null,
                "reviewer-c", "旧规则已不再安全",
                java.time.Instant.parse("2026-07-29T10:10:00Z"),
                sop("legacy-approved", "deprecated", false),
                java.time.Instant.parse("2026-07-29T10:00:00Z"),
                java.time.Instant.parse("2026-07-29T10:10:00Z"));
        when(versions.deprecateLegacy(
                7L, "legacy-approved", 1,
                "reviewer-c", "旧规则已不再安全"))
                .thenReturn(retired);

        ApprovedPlaybookVersion result = service(
                mock(TroubleshootingKnowledgeReviewMapper.class),
                mock(KnowledgeReviewSourceReader.class),
                mock(KnowledgePromotionMaterialReader.class),
                versions).deprecateLegacy(
                7L,
                "legacy-approved",
                1,
                "reviewer-c",
                "旧规则已不再安全");

        assertThat(result).isEqualTo(retired);
    }

    private KnowledgeReviewWorkflowService service(
            TroubleshootingKnowledgeReviewMapper mapper,
            KnowledgeReviewSourceReader sources) {
        return service(
                mapper,
                sources,
                mock(KnowledgePromotionMaterialReader.class),
                mock(TroubleshootingPlaybookVersionService.class));
    }

    private KnowledgeReviewWorkflowService service(
            TroubleshootingKnowledgeReviewMapper mapper,
            KnowledgeReviewSourceReader sources,
            KnowledgePromotionMaterialReader materials,
            TroubleshootingPlaybookVersionService versions) {
        return new KnowledgeReviewWorkflowService(
                mapper, sources, materials, versions,
                new ObjectMapper().findAndRegisterModules());
    }

    private KnowledgeReviewSource source() {
        ReferenceSolutionComparator.Comparison comparison =
                new ReferenceSolutionComparator.Comparison(
                        "reference-1", true, 1.0,
                        List.of(), List.of(), List.of(), List.of());
        KnowledgeReviewSnapshot snapshot = new KnowledgeReviewSnapshot(
                "VALID",
                KnowledgeQualificationPhase.CALIBRATION,
                List.of(),
                comparison,
                "model-config-v7",
                "NOT_ELIGIBLE",
                List.of("FIXTURE_ONLY"),
                true);
        return new KnowledgeReviewSource(
                KnowledgeOrigin.EVIDENCE_DERIVED,
                "record-1",
                "csdp:scenario:message_send_failed",
                snapshot);
    }

    private KnowledgeReviewSource eligibleSource() {
        return new KnowledgeReviewSource(
                KnowledgeOrigin.EVIDENCE_DERIVED,
                "record-1",
                "csdp:903001",
                new KnowledgeReviewSnapshot(
                        "VALID",
                        KnowledgeQualificationPhase.CALIBRATION,
                        List.of(),
                        source().snapshot().referenceComparison(),
                        "model-config-v7",
                        "ELIGIBLE_FOR_APPROVAL",
                        List.of(),
                        false));
    }

    private SopEntry candidateSop() {
        return sop("candidate-source", "candidate", false);
    }

    private SopEntry approvedSop(String sopId) {
        return sop(sopId, "approved", true);
    }

    private SopEntry sop(String sopId, String status, boolean verified) {
        return new SopEntry(
                sopId,
                SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP",
                "903001",
                "order-svc",
                "订单服务 Mongo 连接池耗尽",
                "连接池打满",
                "database",
                "DBA 组",
                status,
                verified,
                List.of(new EvidenceRequest(
                        "EV-1", "log_count", "确认发生",
                        Map.of("service", "order-svc"), "-15m", true)),
                List.of(new AnomalyCriterion(
                        "error_present", "EV-1", "错误码日志出现",
                        new Criterion.NumericGte("count", 1))),
                List.of(new DiagnosisRule(
                        "R-a", List.of("error_present"),
                        "Mongo 连接池打满", "连接可用数归零",
                        Confidence.HIGH, false)),
                List.of());
    }

    private TroubleshootingKnowledgeReviewEntity persisted(String status, int version) {
        TroubleshootingKnowledgeReviewEntity entity =
                new TroubleshootingKnowledgeReviewEntity();
        entity.setId(1L);
        entity.setWorkspaceId(7L);
        entity.setReviewId("review-1");
        entity.setOrigin("EVIDENCE_DERIVED");
        entity.setSourceRecordId("record-1");
        entity.setSelectorKey("csdp:scenario:message_send_failed");
        entity.setStatus(status);
        entity.setReviewer("reviewer-a");
        entity.setReason("核对固定回放与引用");
        try {
            entity.setSnapshotJson(new ObjectMapper().findAndRegisterModules()
                    .writeValueAsString(source().snapshot()));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        entity.setVersion(version);
        entity.setDeleted(0);
        entity.setCreateTime(LocalDateTime.parse("2026-07-29T10:00:00"));
        entity.setUpdateTime(LocalDateTime.parse("2026-07-29T10:00:00"));
        return entity;
    }
}
