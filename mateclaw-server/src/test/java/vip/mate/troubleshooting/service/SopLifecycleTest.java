package vip.mate.troubleshooting.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.model.TroubleshootingSopEntity;
import vip.mate.troubleshooting.repository.TroubleshootingSopMapper;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;
import vip.mate.troubleshooting.synthesis.KnowledgeQualificationPhase;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewSnapshot;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The review lifecycle of the knowledge the deterministic path runs on.
 *
 * <p>Promotion is the moment unreviewed knowledge starts driving real
 * conclusions, so these tests pin that it only moves forward, that it cannot be
 * undone by flipping a field back, and that approving leaves the SOP actually
 * operational rather than half-promoted.</p>
 */
class SopLifecycleTest {

    private static final long WORKSPACE_ID = 1L;

    private final Map<String, TroubleshootingSopEntity> rows = new LinkedHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private TroubleshootingPlaybookVersionService playbookVersions;
    private TroubleshootingSopPersistenceService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                TroubleshootingSopEntity.class);
    }

    @BeforeEach
    void setUp() {
        playbookVersions = mock(TroubleshootingPlaybookVersionService.class);
        service = new TroubleshootingSopPersistenceService(
                sopMapper(), playbookVersions,
                new KnowledgeEvidenceSelectorInventory(objectMapper), objectMapper);
    }

    @Test
    void registersACandidateAndKeepsItOutOfTheDeterministicPath() {
        service.register(WORKSPACE_ID, sop("candidate", false));

        assertThat(service.find(WORKSPACE_ID, "CSDP", "903001")).isNull();
        SopEntry stored = service.findLatest(WORKSPACE_ID, "CSDP", "903001");
        assertThat(stored.status()).isEqualTo("candidate");
        assertThat(stored.operational())
                .as("an unreviewed SOP must not drive conclusions")
                .isFalse();
    }

    @Test
    void refusesRegistrationThatBypassesTheReviewLifecycle() {
        assertThatThrownBy(() -> service.register(WORKSPACE_ID, sop("approved", false)))
                .as("registration must not put unreviewed knowledge directly on the hit path")
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("candidate")
                .hasMessageContaining("verified=false");
        assertThatThrownBy(() -> service.register(WORKSPACE_ID, sop("candidate", true)))
                .as("verified is set only by the explicit candidate -> approved transition")
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("candidate")
                .hasMessageContaining("verified=false");
        assertThat(rows).isEmpty();
    }

    @Test
    void refusesASecondManualSourceWithTheSameStableId() {
        service.register(WORKSPACE_ID, sop("candidate", false));

        assertThatThrownBy(() -> service.register(WORKSPACE_ID, sop("candidate", false)))
                .as("one stable source id must never overwrite another source")
                .isInstanceOf(MateClawException.class);
    }

    @Test
    void allowsDistinctManualSourcesToProposeAReplacementForTheSameSelector() {
        service.register(WORKSPACE_ID, sop("candidate", false));
        service.register(WORKSPACE_ID, sopWithId(
                "manual-replacement-2", "candidate", false));

        List<SopRegistryRecord> sources = service.listRecords(
                WORKSPACE_ID, "candidate", null, 50);

        assertThat(sources).extracting(record -> record.summary().sopId())
                .containsExactlyInAnyOrder("sop-903001", "manual-replacement-2");
        assertThat(sources).extracting(record -> record.summary().routeKey())
                .containsOnly("csdp:903001");
    }

    @Test
    void refusesLegacyApprovalThatBypassesEligibilityAndVersionGates() {
        service.register(WORKSPACE_ID, sop("candidate", false));

        assertThatThrownBy(() ->
                service.updateStatus(WORKSPACE_ID, "CSDP", "903001", "approved"))
                .as("legacy status mutation must not bypass source eligibility, replay, "
                        + "optimistic locking, or new-version promotion")
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("eligibility gate")
                .hasMessageContaining("new version");

        assertThat(service.find(WORKSPACE_ID, "CSDP", "903001")).isNull();
        assertThat(service.findLatest(WORKSPACE_ID, "CSDP", "903001").operational())
                .isFalse();
    }

    @Test
    void refusesToDemoteAnApprovedSopBackToCandidate() {
        seedApprovedSop();

        assertThatThrownBy(() ->
                service.updateStatus(WORKSPACE_ID, "CSDP", "903001", "candidate"))
                .as("a mistaken approval can only move forward to deprecated, "
                        + "which preserves its review trail")
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("illegal SOP transition");
    }

    @Test
    void refusesToDeprecateSomethingNeverApproved() {
        service.register(WORKSPACE_ID, sop("candidate", false));

        assertThatThrownBy(() ->
                service.updateStatus(WORKSPACE_ID, "CSDP", "903001", "deprecated"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("illegal SOP transition");
    }

    @Test
    void deprecatingRetiresAnApprovedSopFromTheDeterministicPath() {
        seedApprovedSop();

        SopEntry retired = service.updateStatus(WORKSPACE_ID, "CSDP", "903001", "deprecated");

        assertThat(retired.operational()).isFalse();
    }

    @Test
    void reportsAnUnknownRouteRatherThanCreatingOne() {
        assertThatThrownBy(() ->
                service.updateStatus(WORKSPACE_ID, "CSDP", "999999", "approved"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("no SOP registered");
    }

    @Test
    void listsWhatIsOperationalSoAReviewerCanSeeWhatIsLive() {
        seedApprovedSop();

        List<SopSummary> all = service.list(WORKSPACE_ID, null, null, 50);

        assertThat(all).hasSize(1);
        assertThat(all.getFirst().operational()).isTrue();
        assertThat(all.getFirst().routeKey()).isEqualTo("csdp:903001");
    }

    @Test
    void listsAFullCandidateContractWithTheSameIndexedIdentity() {
        SopEntry candidate = service.register(
                WORKSPACE_ID, sop("candidate", false));

        List<SopRegistryRecord> records =
                service.listRecords(WORKSPACE_ID, "candidate", null, 50);

        assertThat(records).hasSize(1);
        assertThat(records.getFirst().entry()).isEqualTo(candidate);
        assertThat(records.getFirst().summary().sopId())
                .isEqualTo(candidate.sopId());
        assertThat(records.getFirst().summary().routeKey())
                .isEqualTo(candidate.routingKey());
    }

    @Test
    void findsOneManualCandidateByWorkspaceAndStableSopId() throws Exception {
        TroubleshootingSopMapper mapper = mock(TroubleshootingSopMapper.class);
        SopEntry source = sop("candidate", false);
        TroubleshootingSopEntity entity = new TroubleshootingSopEntity();
        entity.setWorkspaceId(WORKSPACE_ID);
        entity.setSopId(source.sopId());
        entity.setAggregateJson(objectMapper.writeValueAsString(source));
        entity.setDeleted(0);
        when(mapper.selectOne(any())).thenReturn(entity);
        TroubleshootingSopPersistenceService direct =
                new TroubleshootingSopPersistenceService(
                        mapper,
                        mock(TroubleshootingPlaybookVersionService.class),
                        new KnowledgeEvidenceSelectorInventory(objectMapper),
                        objectMapper);

        SopEntry found = direct.findBySopId(WORKSPACE_ID, source.sopId());

        assertThat(found).isEqualTo(source);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<AbstractWrapper<TroubleshootingSopEntity, ?, ?>> query =
                ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(mapper).selectOne(query.capture());
        assertThat(bound(query.getValue()).values())
                .contains(WORKSPACE_ID, source.sopId(), 0);
    }

    @Test
    void latestVersionedAuthorityWinsOverTheLegacyCandidateRow() {
        service.register(WORKSPACE_ID, sop("candidate", false));
        SopEntry approved = new SopEntry(
                "playbook-v2", SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP", "903001", "order-svc", "版本化权威",
                "已审核根因", "database", "DBA 组", "approved", true,
                sop("candidate", false).evidenceRequests(),
                sop("candidate", false).anomalyCriteria(),
                sop("candidate", false).diagnosisRules(),
                List.of());
        when(playbookVersions.findCurrent(WORKSPACE_ID, "csdp:903001"))
                .thenReturn(Optional.of(new ApprovedPlaybookVersion(
                        "playbook-v2", 2, "csdp:903001", "APPROVED",
                        "MANUAL", "sop-903001", "review-2", 1,
                        "reviewer-a", "固定回放通过",
                        new KnowledgeReviewSnapshot(
                                "VALID", KnowledgeQualificationPhase.NOT_APPLICABLE,
                                List.of(), null, null,
                                "ELIGIBLE_FOR_APPROVAL", List.of(), false),
                        approved,
                        java.time.Instant.parse("2026-07-29T10:00:00Z"),
                        java.time.Instant.parse("2026-07-29T10:00:00Z"))));

        SopEntry found = service.find(WORKSPACE_ID, "CSDP", "903001");

        assertThat(found.sopId()).isEqualTo("playbook-v2");
        assertThat(found.operational()).isTrue();
    }

    @Test
    void deprecatedLatestVersionIsARouteMissAndNeverResurrectsLegacyAuthority() {
        seedApprovedSop();
        SopEntry deprecated = sopWithId("playbook-v2", "deprecated", false);
        when(playbookVersions.findCurrent(WORKSPACE_ID, "csdp:903001"))
                .thenReturn(Optional.of(new ApprovedPlaybookVersion(
                        "playbook-v2", 2, "csdp:903001", "DEPRECATED",
                        "MANUAL", "sop-903001", "review-2", 1,
                        "reviewer-a", "固定回放通过", null,
                        deprecated,
                        java.time.Instant.parse("2026-07-29T10:00:00Z"),
                        java.time.Instant.parse("2026-07-29T10:10:00Z"))));

        assertThat(service.find(WORKSPACE_ID, "CSDP", "903001")).isNull();
        assertThat(service.findLatest(WORKSPACE_ID, "CSDP", "903001"))
                .isEqualTo(deprecated);
    }

    @Test
    void registryShowsTheVersionedAuthorityInsteadOfItsLegacySourceCandidate() {
        service.register(WORKSPACE_ID, sop("candidate", false));
        SopSummary versioned = new SopSummary(
                "playbook-v2", "csdp:903001", "CSDP", "903001",
                "order-svc", "approved", true, true,
                LocalDateTime.parse("2026-07-29T10:00:00"),
                LocalDateTime.parse("2026-07-29T10:00:00"));
        when(playbookVersions.listLatest(WORKSPACE_ID, null, null, 50))
                .thenReturn(List.of(versioned));

        List<SopSummary> listed = service.list(WORKSPACE_ID, null, null, 50);

        assertThat(listed).containsExactly(versioned);
    }

    /**
     * 列表发出去的 id，详情接口必须认得——两个接口要么一起对，要么就是坏的。
     *
     * <p>{@code list} 会用版本行覆盖同一 selector 的注册行，于是 {@code sopId}
     * 装的是版本表的 {@code playbook-*}；而 {@code findBySopId} 此前只查注册表。
     * 结果是浏览知识库时**恰好是最重要的那些行（operational）点不开**。</p>
     *
     * <p>断言写成「遍历列表拿到的每一个 id」而不是写死某一种，正是因为这里有两个
     * 身份空间：只钉一种，另一种坏掉时这条测试照样绿。</p>
     */
    @Test
    void everyIdTheRegistryListingHandsOutCanBeResolvedByTheDetailLookup() {
        service.register(WORKSPACE_ID, sop("candidate", false));
        // 另一条 selector：同一条 selector 上的注册行会被版本行盖掉，只留一种身份，
        // 那样这条测试就退化成「只钉了 playbook-*」。
        service.register(WORKSPACE_ID, sopOnOtherSelector("manual-other-2"));
        SopSummary versioned = new SopSummary(
                "playbook-v2", "csdp:903001", "CSDP", "903001",
                "order-svc", "approved", true, true,
                LocalDateTime.parse("2026-07-29T10:00:00"),
                LocalDateTime.parse("2026-07-29T10:00:00"),
                2, "MANUAL", "sop-903001", "review-2", 1);
        when(playbookVersions.listLatest(WORKSPACE_ID, null, null, 50))
                .thenReturn(List.of(versioned));
        when(playbookVersions.findByPlaybookId(WORKSPACE_ID, "playbook-v2"))
                .thenReturn(Optional.of(new ApprovedPlaybookVersion(
                        "playbook-v2", 2, "csdp:903001", "APPROVED",
                        "MANUAL", "sop-903001", "review-2", 1,
                        "reviewer-a", "固定回放通过", null,
                        sopWithId("sop-903001", "approved", true),
                        java.time.Instant.parse("2026-07-29T10:00:00Z"),
                        java.time.Instant.parse("2026-07-29T10:00:00Z"))));

        List<SopSummary> listed = service.list(WORKSPACE_ID, null, null, 50);

        assertThat(listed).extracting(SopSummary::sopId)
                .as("两个身份空间都要出现，否则这条测试证明不了什么")
                .containsExactlyInAnyOrder("playbook-v2", "manual-other-2");
        for (SopSummary row : listed) {
            assertThat(service.findBySopId(WORKSPACE_ID, row.sopId()))
                    .as("列表发出了 %s，详情就必须认得它", row.sopId())
                    .isNotNull();
        }
    }

    /** 认两种身份不等于认所有字符串：查不到仍然是查不到。 */
    @Test
    void anIdFromNeitherIdentitySpaceStillResolvesToNothing() {
        when(playbookVersions.findByPlaybookId(WORKSPACE_ID, "playbook-nonexistent"))
                .thenReturn(Optional.empty());

        assertThat(service.findBySopId(WORKSPACE_ID, "playbook-nonexistent")).isNull();
    }

    @Test
    void statusFilterCannotResurrectASourceCandidateBehindAnApprovedVersion() {
        service.register(WORKSPACE_ID, sop("candidate", false));
        SopSummary versioned = new SopSummary(
                "playbook-v2", "csdp:903001", "CSDP", "903001",
                "order-svc", "approved", true, true,
                LocalDateTime.parse("2026-07-29T10:00:00"),
                LocalDateTime.parse("2026-07-29T10:00:00"),
                2, "MANUAL", "sop-903001", "review-2", 1);
        when(playbookVersions.listLatest(WORKSPACE_ID, null, null, 500))
                .thenReturn(List.of(versioned));

        List<SopSummary> listed = service.list(
                WORKSPACE_ID, "candidate", null, 50);

        assertThat(listed).isEmpty();
    }

    @Test
    void compatibilityRetirementCannotBypassTheVersionedReviewLedger() {
        ApprovedPlaybookVersion active = new ApprovedPlaybookVersion(
                "playbook-v2", 2, "csdp:903001", "APPROVED",
                "MANUAL", "sop-903001", "review-2", 1,
                "reviewer-a", "固定回放通过", null,
                sop("approved", true),
                java.time.Instant.parse("2026-07-29T10:00:00Z"),
                java.time.Instant.parse("2026-07-29T10:00:00Z"));
        when(playbookVersions.findCurrent(WORKSPACE_ID, "csdp:903001"))
                .thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.updateStatus(
                WORKSPACE_ID, "CSDP", "903001", "deprecated"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("knowledge-review")
                .hasMessageContaining("exact review version");

        verify(playbookVersions, org.mockito.Mockito.never())
                .deprecateByReview(
                        anyLong(), anyString(), anyString(), anyString());
    }

    // ---------- fixtures ----------

    private SopEntry sop(String status, boolean verified) {
        return sopWithId("sop-903001", status, verified);
    }

    private SopEntry sopWithId(String sopId, String status, boolean verified) {
        return new SopEntry(
                sopId, SopEntry.CURRENT_CONTRACT_VERSION, "CSDP", "903001", "order-svc",
                "订单服务 Mongo 连接池耗尽", "连接池打满", "database", "DBA 组", status, verified,
                List.of(new EvidenceRequest("EV-1", "log_count", "确认发生",
                        Map.of("service", "order-svc"), "-15m", true)),
                List.of(new AnomalyCriterion("error_present", "EV-1", "错误码日志出现",
                        new Criterion.NumericGte("count", 1))),
                List.of(new DiagnosisRule("R-a", List.of("error_present"),
                        "Mongo 连接池打满", "连接可用数归零", Confidence.HIGH, false)),
                List.of());
    }

    /** A candidate on a selector no versioned authority owns, so it survives the merge. */
    private SopEntry sopOnOtherSelector(String sopId) {
        return new SopEntry(
                sopId, SopEntry.CURRENT_CONTRACT_VERSION, "CSDP", "903002", "order-svc",
                "订单服务会话超时", "会话超时", "network", "会话组", "candidate", false,
                List.of(new EvidenceRequest("EV-1", "log_count", "确认发生",
                        Map.of("service", "order-svc"), "-15m", true)),
                List.of(new AnomalyCriterion("error_present", "EV-1", "错误码日志出现",
                        new Criterion.NumericGte("count", 1))),
                List.of(new DiagnosisRule("R-a", List.of("error_present"),
                        "会话超时", "连接中断", Confidence.HIGH, false)),
                List.of());
    }

    /** Existing approved rows predate the versioned promotion command. */
    private void seedApprovedSop() {
        SopEntry approved = sop("approved", true);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TroubleshootingSopEntity entity = new TroubleshootingSopEntity();
        entity.setId(1L);
        entity.setWorkspaceId(WORKSPACE_ID);
        entity.setSopId(approved.sopId());
        entity.setRouteKey(approved.routingKey());
        entity.setSystem(approved.system());
        entity.setErrorCode(approved.errorCode());
        entity.setService(approved.service());
        entity.setStatus(approved.status());
        entity.setVerified(approved.verified());
        entity.setContractVersion(approved.contractVersion());
        try {
            entity.setAggregateJson(objectMapper.writeValueAsString(approved));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        entity.setVersion(0);
        entity.setDeleted(0);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        rows.put(entity.getSopId(), entity);
    }

    private TroubleshootingSopMapper sopMapper() {
        TroubleshootingSopMapper mapper = mock(TroubleshootingSopMapper.class);
        AtomicLong ids = new AtomicLong(1);
        when(mapper.insert(any(TroubleshootingSopEntity.class))).thenAnswer((Answer<Integer>) call -> {
            TroubleshootingSopEntity entity = call.getArgument(0);
            entity.setId(ids.getAndIncrement());
            rows.put(entity.getSopId(), entity);
            return 1;
        });
        when(mapper.selectOne(any())).thenAnswer((Answer<TroubleshootingSopEntity>) call ->
                rows.values().stream()
                        .filter(row -> {
                            Map<String, Object> values = bound(call.getArgument(0));
                            boolean identityMatches = values.containsValue(row.getSopId())
                                    || values.containsValue(row.getRouteKey());
                            boolean statusMatches = !values.containsValue("approved")
                                    || "approved".equals(row.getStatus());
                            return identityMatches && statusMatches;
                        })
                        .findFirst()
                        .orElse(null));
        when(mapper.selectList(any())).thenAnswer((Answer<List<TroubleshootingSopEntity>>) call -> {
            Map<String, Object> values = bound(call.getArgument(0));
            return rows.values().stream()
                    .filter(row -> !values.containsValue("candidate")
                            || "candidate".equals(row.getStatus()))
                    .filter(row -> !values.containsValue("approved")
                            || "approved".equals(row.getStatus()))
                    .toList();
        });
        when(mapper.update(any(TroubleshootingSopEntity.class), any()))
                .thenAnswer((Answer<Integer>) call -> {
                    TroubleshootingSopEntity patch = call.getArgument(0);
                    Map<String, Object> values = bound(call.getArgument(1));
                    TroubleshootingSopEntity row = rows.values().stream()
                            .filter(candidate -> values.containsValue(candidate.getSopId())
                                    || values.containsValue(candidate.getRouteKey()))
                            .findFirst()
                            .orElse(null);
                    if (row == null) {
                        return 0;
                    }
                    row.setStatus(patch.getStatus());
                    row.setVerified(patch.getVerified());
                    row.setAggregateJson(patch.getAggregateJson());
                    return 1;
                });
        return mapper;
    }

    /** Bound parameters materialize lazily, so ask for the SQL segment first. */
    private Map<String, Object> bound(Object wrapper) {
        if (!(wrapper instanceof AbstractWrapper<?, ?, ?> typed)) {
            return Map.of();
        }
        typed.getSqlSegment();
        Map<String, Object> params = typed.getParamNameValuePairs();
        return params == null ? Map.of() : params;
    }
}
