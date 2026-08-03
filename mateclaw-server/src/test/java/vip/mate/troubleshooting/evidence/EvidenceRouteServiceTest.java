package vip.mate.troubleshooting.evidence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.TroubleshootingEvidenceRouteEntity;
import vip.mate.troubleshooting.repository.TroubleshootingEvidenceRouteMapper;

import java.time.Instant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 让一个 workspace 自己声明取证路由——此前这张表只在 application.yml 里。
 *
 * <p>这些用例守的是那条界线：租户只能在**已启用的源之间做选择**，不能引入一个新
 * 的源，也不能声明一条永远取不到东西的路由而毫不知情。</p>
 */
class EvidenceRouteServiceTest {

    private static final long WORKSPACE_ID = 7L;

    private final Map<Long, TroubleshootingEvidenceRouteEntity> rows = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private EvidenceRouteService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                TroubleshootingEvidenceRouteEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new EvidenceRouteService(
                routeMapper(),
                List.of(
                        new StubAdapter("recorded-replay", EvidenceSourceHealth.Status.READY,
                                Set.of("log_count", "log_search")),
                        new StubAdapter("guance", EvidenceSourceHealth.Status.DISABLED,
                                Set.of("log_count", "log_search", "metric"))));
    }

    @Test
    @DisplayName("声明后，router 读到的就是这条路由")
    void aDeclarationBecomesTheRouteTheRouterReads() {
        service.declare(WORKSPACE_ID, "ACME", "log_count",
                List.of("recorded-replay"), "admin", "接入 ACME 的第一条取证路由");

        assertThat(service.find(WORKSPACE_ID, "acme", "log_count"))
                .contains(List.of("recorded-replay"));
        assertThat(service.find(WORKSPACE_ID, "ACME", "LOG_COUNT"))
                .as("大小写不该决定一条请求打到哪儿")
                .contains(List.of("recorded-replay"));
    }

    /**
     * 别的 workspace 读不到——这正是本特性收窄的那一点：YAML 那张表只按 system
     * 名字索引，谁把系统命名成 CSDP 谁就继承 CSDP 的路由。
     */
    @Test
    @DisplayName("声明只对声明它的 workspace 生效")
    void aDeclarationDoesNotLeakToAnotherWorkspace() {
        service.declare(WORKSPACE_ID, "ACME", "log_count",
                List.of("recorded-replay"), "admin", "接入 ACME");

        assertThat(service.find(WORKSPACE_ID + 1, "ACME", "log_count")).isEmpty();
    }

    /**
     * 「没声明过」要回落到部署级配置，「声明了但为空」是租户明说不取证。两者读成
     * 同一件事，就会出现「说了不要还是去问了生产观测系统」。
     */
    @Test
    @DisplayName("没声明 → empty；声明为空 → 一个空答案，不是没答")
    void anEmptyDeclarationIsAnAnswerAndNotAnAbsence() {
        assertThat(service.find(WORKSPACE_ID, "ACME", "metric")).isEmpty();

        service.declare(WORKSPACE_ID, "ACME", "metric",
                List.of(), "admin", "这一格暂不取证");

        assertThat(service.find(WORKSPACE_ID, "ACME", "metric")).contains(List.of());
    }

    @Test
    @DisplayName("只能选已装的平台，拒绝时把有哪些说出来")
    void anUnknownPlatformIsRefusedWithTheListOfRealOnes() {
        assertThatThrownBy(() -> service.declare(WORKSPACE_ID, "ACME", "log_count",
                List.of("datadog"), "admin", "手滑"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("datadog")
                .hasMessageContaining("recorded-replay")
                .hasMessageContaining("guance");
    }

    /** 词表之外的 signal 永远取不到合法结果，声明它只可能是打错字。 */
    @Test
    @DisplayName("只能声明平台认识的 signal，拒绝时把词表说出来")
    void anUnknownSignalKindIsRefusedWithTheVocabulary() {
        assertThatThrownBy(() -> service.declare(WORKSPACE_ID, "ACME", "log_conut",
                List.of("recorded-replay"), "admin", "手滑"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("log_conut")
                .hasMessageContaining("log_count");
    }

    /**
     * 声明成功仍然算成功，但要把话说全：路由指向一个此刻关着的源时，取证会安静地
     * 回 MISSING——安静正是最难查的那种坏。
     */
    @Test
    @DisplayName("回显里逐个平台报当下可用性，且不冒充 owner 验收")
    void theViewReportsPerPlatformAvailability() {
        EvidenceRouteView view = service.declare(WORKSPACE_ID, "ACME", "log_search",
                List.of("recorded-replay", "guance"), "admin", "两条退路");

        assertThat(view.platforms()).containsExactly("recorded-replay", "guance");
        assertThat(view.platformStates())
                .extracting(EvidenceRouteView.PlatformState::platform,
                        EvidenceRouteView.PlatformState::available)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("recorded-replay", true),
                        org.assertj.core.api.Assertions.tuple("guance", false));
    }

    /** 适配器不服务这条 signal 时，健康是绿的也不能报「能取」。 */
    @Test
    @DisplayName("READY 但不支持这条 signal → 不可用")
    void aReadyAdapterThatDoesNotServeTheSignalIsNotAvailable() {
        EvidenceRouteView view = service.declare(WORKSPACE_ID, "ACME", "metric",
                List.of("recorded-replay"), "admin", "错配");

        assertThat(view.platformStates()).singleElement()
                .extracting(EvidenceRouteView.PlatformState::available)
                .isEqualTo(false);
    }

    @Test
    @DisplayName("同一格再声明一次是替换，不是并存")
    void redeclaringReplacesInsteadOfAccumulating() {
        service.declare(WORKSPACE_ID, "ACME", "log_count",
                List.of("guance"), "admin", "先接 guance");
        service.declare(WORKSPACE_ID, "ACME", "log_count",
                List.of("recorded-replay"), "admin", "改回放");

        assertThat(service.find(WORKSPACE_ID, "ACME", "log_count"))
                .contains(List.of("recorded-replay"));
        assertThat(rows).hasSize(1);
    }

    @Test
    @DisplayName("路由改动必须留下 actor 和原因")
    void aRouteChangeMustCarryAnActorAndAReason() {
        assertThatThrownBy(() -> service.declare(WORKSPACE_ID, "ACME", "log_count",
                List.of("recorded-replay"), "admin", "  "))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("reason");
    }

    @Test
    @DisplayName("同一个平台不得列两遍")
    void aPlatformMustNotAppearTwiceInOneRoute() {
        assertThatThrownBy(() -> service.declare(WORKSPACE_ID, "ACME", "log_count",
                List.of("recorded-replay", "RECORDED-REPLAY"), "admin", "重复"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("twice");
    }

    @SuppressWarnings("unchecked")
    private TroubleshootingEvidenceRouteMapper routeMapper() {
        TroubleshootingEvidenceRouteMapper mapper =
                mock(TroubleshootingEvidenceRouteMapper.class);
        when(mapper.insert(any(TroubleshootingEvidenceRouteEntity.class)))
                .thenAnswer((Answer<Integer>) call -> {
                    TroubleshootingEvidenceRouteEntity entity = call.getArgument(0);
                    entity.setId(ids.incrementAndGet());
                    rows.put(entity.getId(), entity);
                    return 1;
                });
        when(mapper.updateById(any(TroubleshootingEvidenceRouteEntity.class)))
                .thenAnswer((Answer<Integer>) call -> {
                    TroubleshootingEvidenceRouteEntity entity = call.getArgument(0);
                    rows.put(entity.getId(), entity);
                    return 1;
                });
        when(mapper.selectOne(any())).thenAnswer((Answer<Object>) call ->
                matching(call.getArgument(0)).stream().findFirst().orElse(null));
        when(mapper.selectList(any())).thenAnswer((Answer<Object>) call ->
                matching(call.getArgument(0)));
        when(mapper.deleteById(any(java.io.Serializable.class)))
                .thenAnswer((Answer<Integer>) call ->
                        rows.remove(((Number) call.getArgument(0)).longValue()) == null ? 0 : 1);
        return mapper;
    }

    /**
     * 只按查询里绑定的值筛。
     *
     * <p>刻意**逐字段比对**而不是「参数里出现过这个值就算命中」：workspaceId 是
     * 这里最要紧的一个条件，如果它被宽松地匹配掉，「声明不会泄漏到别的
     * workspace」那条用例就会在真实实现坏掉时照样绿。</p>
     */
    private List<TroubleshootingEvidenceRouteEntity> matching(Object wrapper) {
        Map<String, Object> bound = bound(wrapper);
        return rows.values().stream()
                .filter(row -> !bound.containsValue(row.getWorkspaceId())
                        ? false
                        : true)
                .filter(row -> hasNoStringTerms(bound)
                        || bound.containsValue(row.getSystem()))
                .filter(row -> hasNoStringTerms(bound)
                        || bound.values().stream()
                                .noneMatch(value -> isSignalTerm(value, bound))
                        || bound.containsValue(row.getSignalKind()))
                .toList();
    }

    private boolean hasNoStringTerms(Map<String, Object> bound) {
        return bound.values().stream().noneMatch(String.class::isInstance);
    }

    /** A bound string is a signal term when it is not the system term. */
    private boolean isSignalTerm(Object value, Map<String, Object> bound) {
        return value instanceof String text
                && CanonicalEvidenceSchema.signalKinds().contains(text);
    }

    private Map<String, Object> bound(Object wrapper) {
        if (!(wrapper instanceof AbstractWrapper<?, ?, ?> typed)) {
            return Map.of();
        }
        typed.getSqlSegment();
        Map<String, Object> params = typed.getParamNameValuePairs();
        return params == null ? Map.of() : params;
    }

    private static final class StubAdapter implements EvidenceSourceAdapter {
        private final String platform;
        private final EvidenceSourceHealth.Status status;
        private final java.util.Set<String> signals;

        private StubAdapter(
                String platform,
                EvidenceSourceHealth.Status status,
                java.util.Set<String> signals) {
            this.platform = platform;
            this.status = status;
            this.signals = signals;
        }

        @Override
        public String platform() {
            return platform;
        }

        @Override
        public boolean supports(String signalKind) {
            return signals.contains(signalKind);
        }

        @Override
        public EvidenceResult collect(
                long workspaceId, EvidenceRequest request, IncidentContext incident) {
            return new EvidenceResult(
                    request.requestId(), "L", "", EvidenceStatus.MISSING, "",
                    Map.of(), platform, Instant.EPOCH);
        }

        @Override
        public EvidenceSourceHealth health() {
            return new EvidenceSourceHealth(platform, status, false, "");
        }
    }
}
