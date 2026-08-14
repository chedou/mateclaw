package vip.mate.troubleshooting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.model.TroubleshootingPlaybookVersionEntity;
import vip.mate.troubleshooting.repository.TroubleshootingPlaybookVersionMapper;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;
import vip.mate.troubleshooting.synthesis.KnowledgeOrigin;
import vip.mate.troubleshooting.synthesis.KnowledgePromotionMaterial;
import vip.mate.troubleshooting.synthesis.KnowledgeQualificationPhase;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewSnapshot;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TroubleshootingPlaybookVersionServiceTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void resolvesOnlyOneActiveSystemForAnExactRoute() {
        TroubleshootingPlaybookVersionMapper mapper =
                mock(TroubleshootingPlaybookVersionMapper.class);
        TroubleshootingPlaybookVersionService service =
                new TroubleshootingPlaybookVersionService(mapper, objectMapper);
        when(mapper.listActiveSystemsForExactRoute(
                7L, "csdp-wechat", "904003"))
                .thenReturn(List.of("CSDP"));

        assertThat(service.uniqueActiveSystemForExactRoute(
                7L, "csdp-wechat", "904003"))
                .contains("CSDP");
    }

    @Test
    void refusesAnAmbiguousExactRouteWithoutPickingTheFirstSystem() {
        TroubleshootingPlaybookVersionMapper mapper =
                mock(TroubleshootingPlaybookVersionMapper.class);
        TroubleshootingPlaybookVersionService service =
                new TroubleshootingPlaybookVersionService(mapper, objectMapper);
        when(mapper.listActiveSystemsForExactRoute(
                7L, "shared-service", "904003"))
                .thenReturn(List.of("CSDP", "OTHER"));

        assertThat(service.uniqueActiveSystemForExactRoute(
                7L, "shared-service", "904003"))
                .isEmpty();
    }

    @Test
    void resolvesOnlyTheExactImmutablePlaybookVersion() {
        TroubleshootingPlaybookVersionMapper mapper =
                mock(TroubleshootingPlaybookVersionMapper.class);
        TroubleshootingPlaybookVersionService service =
                new TroubleshootingPlaybookVersionService(mapper, objectMapper);
        TroubleshootingPlaybookVersionEntity version = approvedEntity(
                "playbook-old", 3, "review-old", sop("playbook-old", "approved", true));
        when(mapper.findByPlaybookId(7L, "playbook-old")).thenReturn(version);

        assertThat(service.findByRef(
                7L, new PlaybookVersionRef("playbook-old", 3)))
                .get()
                .extracting(
                        ApprovedPlaybookVersion::playbookId,
                        ApprovedPlaybookVersion::playbookVersion)
                .containsExactly("playbook-old", 3);
        assertThat(service.findByRef(
                7L, new PlaybookVersionRef("playbook-old", 2))).isEmpty();
    }

    @Test
    void locksOnlyTheStillActiveApprovedAuthorityBeforeDiagnosisPersistence() {
        TroubleshootingPlaybookVersionMapper mapper =
                mock(TroubleshootingPlaybookVersionMapper.class);
        TroubleshootingPlaybookVersionService service =
                new TroubleshootingPlaybookVersionService(mapper, objectMapper);
        TroubleshootingPlaybookVersionEntity version = approvedEntity(
                "playbook-active", 3, "review-active",
                sop("playbook-active", "approved", true));
        when(mapper.lockActiveApprovedByPlaybookId(7L, "playbook-active"))
                .thenReturn(version);

        assertThat(service.lockActiveApprovedByPlaybookId(7L, "playbook-active"))
                .get()
                .extracting(
                        ApprovedPlaybookVersion::playbookId,
                        ApprovedPlaybookVersion::status)
                .containsExactly("playbook-active", "APPROVED");
    }

    @Test
    void approvalCreatesANewVersionAndDeprecatesTheExactFrozenAuthority() throws Exception {
        TroubleshootingPlaybookVersionMapper mapper =
                mock(TroubleshootingPlaybookVersionMapper.class);
        TroubleshootingPlaybookVersionService service =
                new TroubleshootingPlaybookVersionService(mapper, objectMapper);
        TroubleshootingPlaybookVersionEntity current = approvedEntity(
                "playbook-old", 3, "review-old", sop("playbook-old", "approved", true));
        when(mapper.findByReview(7L, "review-new")).thenReturn(null);
        when(mapper.findActive(7L, "csdp:903001")).thenReturn(current);
        when(mapper.maxPlaybookVersion(7L, "csdp:903001")).thenReturn(3);
        when(mapper.retireActive(
                eq(7L), eq(current.getId()), eq("csdp:903001"), eq(0),
                any(String.class), eq("reviewer-a"),
                eq("superseded by approved review review-new"),
                any(LocalDateTime.class)))
                .thenReturn(1);
        when(mapper.insert(any(TroubleshootingPlaybookVersionEntity.class)))
                .thenAnswer(call -> {
                    TroubleshootingPlaybookVersionEntity inserted = call.getArgument(0);
                    inserted.setId(42L);
                    return 1;
                });

        ApprovedPlaybookVersion approved = service.promote(
                7L,
                new KnowledgePromotionMaterial(
                        KnowledgeOrigin.MANUAL,
                        "manual-candidate-1",
                        "csdp:903001",
                        KnowledgeEvidenceGrade.AUTHORED_FIXTURE,
                        sop("manual-candidate-1", "candidate", false)),
                "review-new",
                1,
                true,
                "playbook-old",
                3,
                "reviewer-a",
                "固定正负例均通过",
                eligibleSnapshot());

        assertThat(approved.playbookVersion()).isEqualTo(4);
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.playbook().status()).isEqualTo("approved");
        assertThat(approved.playbook().verified()).isTrue();
        assertThat(approved.playbook().sopId()).isEqualTo(approved.playbookId());
        assertThat(approved.knowledgeEvidenceGrade())
                .isEqualTo(KnowledgeEvidenceGrade.AUTHORED_FIXTURE);

        ArgumentCaptor<TroubleshootingPlaybookVersionEntity> inserted =
                ArgumentCaptor.forClass(TroubleshootingPlaybookVersionEntity.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getPlaybookVersion()).isEqualTo(4);
        assertThat(inserted.getValue().getSelectorKey()).isEqualTo("csdp:903001");
        assertThat(inserted.getValue().getActiveSelectorKey()).isEqualTo("csdp:903001");
        assertThat(inserted.getValue().getSourceOrigin()).isEqualTo("MANUAL");
        assertThat(inserted.getValue().getSourceRecordId()).isEqualTo("manual-candidate-1");
        assertThat(inserted.getValue().getKnowledgeEvidenceGrade())
                .isEqualTo("AUTHORED_FIXTURE");
        assertThat(inserted.getValue().getReviewId()).isEqualTo("review-new");
        assertThat(inserted.getValue().getReviewVersion()).isEqualTo(1);
        assertThat(inserted.getValue().getApprovalSnapshotJson())
                .contains("ELIGIBLE_FOR_APPROVAL")
                .doesNotContain("searchTerm", "rawLog", "credential");
    }

    @Test
    void changedActiveAuthorityFailsBeforeAnyVersionMutation() {
        TroubleshootingPlaybookVersionMapper mapper =
                mock(TroubleshootingPlaybookVersionMapper.class);
        TroubleshootingPlaybookVersionService service =
                new TroubleshootingPlaybookVersionService(mapper, objectMapper);
        when(mapper.findByReview(7L, "review-new")).thenReturn(null);
        when(mapper.findActive(7L, "csdp:903001")).thenReturn(
                approvedEntity(
                        "playbook-concurrent", 4, "review-concurrent",
                        sop("playbook-concurrent", "approved", true)));

        assertThatThrownBy(() -> service.promote(
                7L,
                new KnowledgePromotionMaterial(
                        KnowledgeOrigin.MANUAL,
                        "manual-candidate-1",
                        "csdp:903001",
                        sop("manual-candidate-1", "candidate", false)),
                "review-new",
                1,
                true,
                "playbook-old",
                3,
                "reviewer-a",
                "固定正负例均通过",
                eligibleSnapshot()))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verify(mapper, never()).retireActive(
                any(Long.class), any(Long.class), any(String.class), any(Integer.class),
                any(String.class), any(String.class), any(String.class),
                any(LocalDateTime.class));
        verify(mapper, never()).insert(any(TroubleshootingPlaybookVersionEntity.class));
    }

    @Test
    void deprecationByReviewCannotRetireAnotherReviewsActiveAuthority() {
        TroubleshootingPlaybookVersionMapper mapper =
                mock(TroubleshootingPlaybookVersionMapper.class);
        TroubleshootingPlaybookVersionService service =
                new TroubleshootingPlaybookVersionService(mapper, objectMapper);
        TroubleshootingPlaybookVersionEntity reviewed = approvedEntity(
                "playbook-reviewed", 3, "review-reviewed",
                sop("playbook-reviewed", "approved", true));
        TroubleshootingPlaybookVersionEntity concurrent = approvedEntity(
                "playbook-concurrent", 4, "review-concurrent",
                sop("playbook-concurrent", "approved", true));
        concurrent.setId(2L);
        when(mapper.findByReview(7L, "review-reviewed")).thenReturn(reviewed);
        when(mapper.findActive(7L, "csdp:903001")).thenReturn(concurrent);

        assertThatThrownBy(() -> service.deprecateByReview(
                7L, "review-reviewed", "reviewer-a", "回放反例"))
                .isInstanceOf(MateClawException.class)
                .extracting(error -> ((MateClawException) error).getCode())
                .isEqualTo(409);

        verify(mapper, never()).retireActive(
                any(Long.class), any(Long.class), any(String.class), any(Integer.class),
                any(String.class), any(String.class), any(String.class),
                any(LocalDateTime.class));
    }

    @Test
    void deprecationByReviewPersistsTheRetirementAudit() {
        TroubleshootingPlaybookVersionMapper mapper =
                mock(TroubleshootingPlaybookVersionMapper.class);
        TroubleshootingPlaybookVersionService service =
                new TroubleshootingPlaybookVersionService(mapper, objectMapper);
        TroubleshootingPlaybookVersionEntity reviewed = approvedEntity(
                "playbook-reviewed", 3, "review-reviewed",
                sop("playbook-reviewed", "approved", true));
        when(mapper.findByReview(7L, "review-reviewed")).thenReturn(reviewed);
        when(mapper.findActive(7L, "csdp:903001")).thenReturn(reviewed);
        when(mapper.retireActive(
                eq(7L), eq(reviewed.getId()), eq("csdp:903001"), eq(0),
                any(String.class), eq("reviewer-a"), eq("固定反例否定旧规则"),
                any(LocalDateTime.class)))
                .thenReturn(1);

        ApprovedPlaybookVersion retired = service.deprecateByReview(
                7L,
                "review-reviewed",
                "reviewer-a",
                "固定反例否定旧规则");

        assertThat(retired.status()).isEqualTo("DEPRECATED");
        assertThat(retired.deprecatedBy()).isEqualTo("reviewer-a");
        assertThat(retired.deprecationReason()).isEqualTo("固定反例否定旧规则");
        assertThat(retired.deprecatedAt()).isNotNull();
    }

    @Test
    void legacyMigrationAuthorityHasAnExactAuditedRetirementPath() {
        TroubleshootingPlaybookVersionMapper mapper =
                mock(TroubleshootingPlaybookVersionMapper.class);
        TroubleshootingPlaybookVersionService service =
                new TroubleshootingPlaybookVersionService(mapper, objectMapper);
        TroubleshootingPlaybookVersionEntity legacy = approvedEntity(
                "legacy-approved", 1, null,
                sop("legacy-approved", "approved", true));
        legacy.setSourceOrigin("LEGACY");
        legacy.setSourceRecordId("legacy-approved");
        when(mapper.findByPlaybookId(7L, "legacy-approved"))
                .thenReturn(legacy);
        when(mapper.findActive(7L, "csdp:903001")).thenReturn(legacy);
        when(mapper.retireActive(
                eq(7L), eq(legacy.getId()), eq("csdp:903001"), eq(0),
                any(String.class), eq("reviewer-a"), eq("旧规则已不再安全"),
                any(LocalDateTime.class)))
                .thenReturn(1);

        ApprovedPlaybookVersion retired = service.deprecateLegacy(
                7L,
                "legacy-approved",
                1,
                "reviewer-a",
                "旧规则已不再安全");

        assertThat(retired.status()).isEqualTo("DEPRECATED");
        assertThat(retired.playbook().operational()).isFalse();
        assertThat(retired.deprecatedBy()).isEqualTo("reviewer-a");
        assertThat(retired.deprecationReason()).isEqualTo("旧规则已不再安全");
        assertThat(retired.deprecatedAt()).isNotNull();
    }

    /**
     * A scenario Playbook is reachable only through the symptoms it declares.
     * Dropping them while freezing the version produces the worst kind of
     * silent failure: the reviewer approves a contract that answers 「URL慢请求」
     * and the live version answers nothing, with every other field intact.
     */
    @Test
    void promotionKeepsTheSymptomsTheApprovedScenarioClaims() {
        TroubleshootingPlaybookVersionMapper mapper =
                mock(TroubleshootingPlaybookVersionMapper.class);
        TroubleshootingPlaybookVersionService service =
                new TroubleshootingPlaybookVersionService(mapper, objectMapper);
        SopEntry candidate = scenarioSop("manual-url-slow", List.of("url慢请求", "慢请求"));
        when(mapper.findByReview(7L, "review-slow")).thenReturn(null);
        when(mapper.findActive(7L, candidate.routingKey())).thenReturn(null);
        when(mapper.maxPlaybookVersion(7L, candidate.routingKey())).thenReturn(null);
        when(mapper.insert(any(TroubleshootingPlaybookVersionEntity.class)))
                .thenAnswer(call -> {
                    ((TroubleshootingPlaybookVersionEntity) call.getArgument(0)).setId(9L);
                    return 1;
                });

        ApprovedPlaybookVersion approved = service.promote(
                7L,
                new KnowledgePromotionMaterial(
                        KnowledgeOrigin.MANUAL,
                        "manual-url-slow",
                        candidate.routingKey(),
                        KnowledgeEvidenceGrade.AUTHORED_FIXTURE,
                        candidate),
                "review-slow",
                1,
                true,
                null,
                null,
                "reviewer-a",
                "固定正负例均通过",
                eligibleSnapshot());

        assertThat(approved.playbook().symptomTriggers())
                .containsExactlyInAnyOrder("url慢请求", "慢请求");
    }

    private SopEntry scenarioSop(String sopId, List<String> symptomTriggers) {
        return new SopEntry(
                sopId, SopEntry.CURRENT_CONTRACT_VERSION, "CSDP",
                "scenario:url_slow_request", "csdp-wechat", "URL 慢请求",
                "", "", "客服组", "candidate", false,
                List.of(), List.of(), List.of(), List.of(), symptomTriggers);
    }

    private TroubleshootingPlaybookVersionEntity approvedEntity(
            String playbookId,
            int playbookVersion,
            String reviewId,
            SopEntry sop) {
        TroubleshootingPlaybookVersionEntity entity =
                new TroubleshootingPlaybookVersionEntity();
        entity.setId(1L);
        entity.setWorkspaceId(7L);
        entity.setPlaybookId(playbookId);
        entity.setSelectorKey("csdp:903001");
        entity.setPlaybookVersion(playbookVersion);
        entity.setActiveSelectorKey("csdp:903001");
        entity.setSystem("CSDP");
        entity.setErrorCode("903001");
        entity.setService("order-svc");
        entity.setStatus("APPROVED");
        entity.setSourceOrigin("MANUAL");
        entity.setSourceRecordId(playbookId);
        entity.setReviewId(reviewId);
        entity.setReviewVersion(1);
        entity.setApprovedBy("reviewer-old");
        entity.setApprovalReason("历史审批");
        entity.setContractVersion(sop.contractVersion());
        try {
            entity.setAggregateJson(objectMapper.writeValueAsString(sop));
            entity.setApprovalSnapshotJson(
                    objectMapper.writeValueAsString(eligibleSnapshot()));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
        entity.setVersion(0);
        entity.setDeleted(0);
        entity.setCreateTime(LocalDateTime.parse("2026-07-29T10:00:00"));
        entity.setUpdateTime(LocalDateTime.parse("2026-07-29T10:00:00"));
        return entity;
    }

    private KnowledgeReviewSnapshot eligibleSnapshot() {
        return new KnowledgeReviewSnapshot(
                "VALID",
                KnowledgeQualificationPhase.NOT_APPLICABLE,
                List.of(),
                null,
                null,
                "ELIGIBLE_FOR_APPROVAL",
                List.of(),
                false);
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
}
