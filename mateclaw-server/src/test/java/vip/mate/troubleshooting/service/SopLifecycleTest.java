package vip.mate.troubleshooting.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
    private TroubleshootingSopPersistenceService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                TroubleshootingSopEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new TroubleshootingSopPersistenceService(sopMapper(), objectMapper);
    }

    @Test
    void registersACandidateAndKeepsItOutOfTheDeterministicPath() {
        service.register(WORKSPACE_ID, sop("candidate", false));

        SopEntry stored = service.find(WORKSPACE_ID, "CSDP", "903001");
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
    void refusesASecondSopOnTheSameRoute() {
        service.register(WORKSPACE_ID, sop("candidate", false));

        assertThatThrownBy(() -> service.register(WORKSPACE_ID, sop("candidate", false)))
                .as("a route collision is how one-code-many-meanings surfaces, "
                        + "instead of one author silently overwriting another")
                .isInstanceOf(MateClawException.class);
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

        assertThat(service.find(WORKSPACE_ID, "CSDP", "903001").operational()).isFalse();
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

    // ---------- fixtures ----------

    private SopEntry sop(String status, boolean verified) {
        return new SopEntry(
                "sop-903001", SopEntry.CURRENT_CONTRACT_VERSION, "CSDP", "903001", "order-svc",
                "订单服务 Mongo 连接池耗尽", "连接池打满", "database", "DBA 组", status, verified,
                List.of(new EvidenceRequest("EV-1", "log_count", "确认发生",
                        Map.of("service", "order-svc"), "-15m", true)),
                List.of(new AnomalyCriterion("error_present", "EV-1", "错误码日志出现",
                        new Criterion.NumericGte("count", 1))),
                List.of(new DiagnosisRule("R-a", List.of("error_present"),
                        "Mongo 连接池打满", "连接可用数归零", Confidence.HIGH, false)),
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
        rows.put(entity.getRouteKey(), entity);
    }

    private TroubleshootingSopMapper sopMapper() {
        TroubleshootingSopMapper mapper = mock(TroubleshootingSopMapper.class);
        AtomicLong ids = new AtomicLong(1);
        when(mapper.insert(any(TroubleshootingSopEntity.class))).thenAnswer((Answer<Integer>) call -> {
            TroubleshootingSopEntity entity = call.getArgument(0);
            entity.setId(ids.getAndIncrement());
            rows.put(entity.getRouteKey(), entity);
            return 1;
        });
        when(mapper.selectOne(any())).thenAnswer((Answer<TroubleshootingSopEntity>) call ->
                rows.values().stream()
                        .filter(row -> bound(call.getArgument(0)).containsValue(row.getRouteKey()))
                        .findFirst()
                        .orElse(null));
        when(mapper.selectList(any())).thenAnswer((Answer<List<TroubleshootingSopEntity>>) call ->
                List.copyOf(rows.values()));
        when(mapper.update(any(TroubleshootingSopEntity.class), any()))
                .thenAnswer((Answer<Integer>) call -> {
                    TroubleshootingSopEntity patch = call.getArgument(0);
                    TroubleshootingSopEntity row = rows.values().stream()
                            .filter(candidate ->
                                    bound(call.getArgument(1)).containsValue(candidate.getRouteKey()))
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
