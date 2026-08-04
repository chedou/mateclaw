package vip.mate.troubleshooting.evidence;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceSourceRouterTest {

    private static final long WORKSPACE_ID = 1L;
    private static final Instant NOW = Instant.parse("2026-07-25T09:12:03Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void fallsBackWhenThePrimarySourceThrows() {
        StubAdapter primary = StubAdapter.throwing("primary");
        StubAdapter fallback = StubAdapter.returning("fallback", result("EV-1", "fallback"));
        EvidenceSourceRouter router = router(
                Map.of("CSDP", Map.of("log_count", List.of("primary", "fallback"))),
                primary, fallback);

        EvidenceResult collected = router.collect(
                WORKSPACE_ID, request("EV-1", "log_count"), incident("CSDP"));

        assertThat(collected.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(collected.source()).isEqualTo("fallback");
        assertThat(primary.calls()).isEqualTo(1);
        assertThat(fallback.calls()).isEqualTo(1);
    }

    @Test
    void treatsAWrongQueryIdAsAnInvalidPrimaryResultAndUsesFallback() {
        StubAdapter primary = StubAdapter.returning("primary", result("WRONG", "primary"));
        StubAdapter fallback = StubAdapter.returning("fallback", result("EV-1", "fallback"));
        EvidenceSourceRouter router = router(
                Map.of("csdp", Map.of("LOG_COUNT", List.of("PRIMARY", "fallback"))),
                primary, fallback);

        EvidenceResult collected = router.collect(
                WORKSPACE_ID, request("EV-1", "log_count"), incident("CSDP"));

        assertThat(collected.source()).isEqualTo("fallback");
        assertThat(primary.calls()).isEqualTo(1);
        assertThat(fallback.calls()).isEqualTo(1);
    }

    @Test
    void restrictedCollectionNeverCallsAnUnpermittedConfiguredSource() {
        StubAdapter guance = StubAdapter.returning("guance", result("EV-1", "guance"));
        StubAdapter replay = StubAdapter.returning(
                "recorded-replay", result("EV-1", "recorded-replay"));
        EvidenceSourceRouter router = router(
                Map.of("CSDP", Map.of(
                        "log_count", List.of("guance", "recorded-replay"))),
                guance, replay);

        EvidenceResult collected = router.collect(
                WORKSPACE_ID,
                request("EV-1", "log_count"),
                incident("CSDP"),
                Set.of("recorded-replay"));

        assertThat(collected.source()).isEqualTo("recorded-replay");
        assertThat(guance.calls()).isZero();
        assertThat(replay.calls()).isEqualTo(1);
    }

    @Test
    void failsClosedWhenEveryConfiguredSourceIsUnavailable() {
        StubAdapter unavailable = StubAdapter.returning(
                "guance", missing("EV-1", "guance:unavailable"));
        EvidenceSourceRouter router = router(
                Map.of("CSDP", Map.of("log_count", List.of("guance"))), unavailable);

        EvidenceResult collected = router.collect(
                WORKSPACE_ID, request("EV-1", "log_count"), incident("CSDP"));

        assertThat(collected.queryId()).isEqualTo("EV-1");
        assertThat(collected.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(collected.source()).isEqualTo("router:unavailable");
        assertThat(collected.collectedAt()).isEqualTo(NOW);
    }

    @Test
    void doesNotGuessASourceWhenNoRouteIsConfigured() {
        StubAdapter adapter = StubAdapter.returning("guance", result("EV-1", "guance"));
        EvidenceSourceRouter router = router(Map.of(), adapter);

        EvidenceResult collected = router.collect(
                WORKSPACE_ID, request("EV-1", "log_count"), incident("CSDP"));

        assertThat(collected.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(collected.source()).isEqualTo("router:unconfigured");
        assertThat(adapter.calls()).isZero();
    }

    @Test
    void capabilityCheckRequiresAnExactConfiguredAndSupportingAdapter() {
        StubAdapter replay = StubAdapter.returning(
                "recorded-replay", result("EV-1", "recorded-replay"));
        EvidenceSourceRouter router = router(
                Map.of("CSDP", Map.of(
                        "log_search", List.of("recorded-replay"))),
                replay);

        assertThat(router.canRoute(WORKSPACE_ID, "csdp", "LOG_SEARCH", "RECORDED-REPLAY"))
                .isTrue();
        assertThat(router.canRoute(WORKSPACE_ID, "CSDP", "log_trace_bundle", "recorded-replay"))
                .isFalse();
        assertThat(router.canRoute(WORKSPACE_ID, "another-system", "log_search", "recorded-replay"))
                .isFalse();
        assertThat(router.canRoute(WORKSPACE_ID, "CSDP", "log_search", "guance"))
                .isFalse();

        StubAdapter unsupported = StubAdapter.unsupported("recorded-replay");
        EvidenceSourceRouter unsupportedRouter = router(
                Map.of("CSDP", Map.of(
                        "log_search", List.of("recorded-replay"))),
                unsupported);
        assertThat(unsupportedRouter.canRoute(
                WORKSPACE_ID, "CSDP", "log_search", "recorded-replay"))
                .isFalse();
    }

    @Test
    void rejectsDuplicatePlatformNamesAtTheCompositionBoundary() {
        EvidenceProperties properties = new EvidenceProperties();

        assertThatThrownBy(() -> new EvidenceSourceRouter(
                List.of(
                        StubAdapter.returning("guance", result("EV-1", "one")),
                        StubAdapter.returning("GUANCE", result("EV-1", "two"))),
                properties,
                CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate evidence platform");
    }

    @Test
    void rejectsAnUnscopedWorkspaceBeforeInvokingAnyAdapter() {
        StubAdapter adapter = StubAdapter.returning("guance", result("EV-1", "guance"));
        EvidenceSourceRouter router = router(
                Map.of("CSDP", Map.of("log_count", List.of("guance"))), adapter);

        assertThatThrownBy(() -> router.collect(
                0L, request("EV-1", "log_count"), incident("CSDP")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspaceId");
        assertThat(adapter.calls()).isZero();
    }

    /**
     * 系统认识、这条信号不认识——**不许拿别的源顶上**。
     *
     * <p>此前这里会落到一层全局默认源（{@code default-sources}）。它从没被设成过
     * 非空值，所以一直没出事；但只要有人填了值，某个已知系统里所有未声明的信号
     * 都会**静默**打到那些源上。取证是 fail-closed 的，路由必须显式。那一层已经
     * 删掉，这条用例是为了它不会以「加个兜底更方便」的名义回来。</p>
     */
    @Test
    void aKnownSystemWithAnUnroutedSignalCollectsNothing() {
        StubAdapter adapter = StubAdapter.returning("guance", result("EV-1", "guance"));
        EvidenceSourceRouter router = router(
                Map.of("CSDP", Map.of("log_count", List.of("guance"))), adapter);

        EvidenceResult collected = router.collect(
                WORKSPACE_ID, request("EV-1", "metric"), incident("CSDP"));

        assertThat(collected.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(collected.source()).isEqualTo("router:unconfigured");
        assertThat(adapter.calls())
                .as("这条信号没有被声明过，就不该有任何适配器被调用")
                .isZero();
    }

    /**
     * 部署级那张表**只按 system 名字索引**，不带 workspace。
     *
     * <p>后果是：另一个租户只要把自己的系统命名成 CSDP，就继承了 CSDP 的路由、
     * 打到 CSDP 的观测端点上。让 workspace 级声明先答，正是为了收窄这一点——它不是
     * 在放宽权限，而是第一次让「哪个租户」进入路由判断。</p>
     */
    @Test
    void aWorkspaceDeclarationOutranksTheDeploymentWideRoute() {
        StubAdapter shared = StubAdapter.returning("guance", result("EV-1", "guance"));
        StubAdapter own = StubAdapter.returning(
                "recorded-replay", result("EV-1", "recorded-replay"));
        EvidenceSourceRouter router = new EvidenceSourceRouter(
                List.of(shared, own),
                deployment(Map.of("CSDP", Map.of("log_count", List.of("guance")))),
                (workspaceId, system, signalKind) -> workspaceId == 7L
                        ? Optional.of(List.of("recorded-replay"))
                        : Optional.empty(),
                CLOCK);

        assertThat(router.collect(
                7L, request("EV-1", "log_count"), incident("CSDP")).source())
                .as("声明过的租户走自己的路")
                .isEqualTo("recorded-replay");
        assertThat(router.collect(
                1L, request("EV-1", "log_count"), incident("CSDP")).source())
                .as("没声明过的租户行为完全不变")
                .isEqualTo("guance");
    }

    /**
     * 「声明了但列表为空」是一个答案：这一格明确不取证。把它读成「没声明」而回落到
     * 部署级路由，等于**租户说了不要，系统照样去问了生产观测系统**。
     */
    @Test
    void anEmptyDeclarationMeansCollectNothingRatherThanFallBack() {
        StubAdapter shared = StubAdapter.returning("guance", result("EV-1", "guance"));
        EvidenceSourceRouter router = new EvidenceSourceRouter(
                List.of(shared),
                deployment(Map.of("CSDP", Map.of("log_count", List.of("guance")))),
                (workspaceId, system, signalKind) -> Optional.of(List.of()),
                CLOCK);

        EvidenceResult collected = router.collect(
                7L, request("EV-1", "log_count"), incident("CSDP"));

        assertThat(collected.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(shared.calls()).as("不能回落去问真源").isZero();
    }

    /** 拒绝要说出下一步，并且要能区分「没配路由」和「这台部署根本没启用源」。 */
    @Test
    void theUnconfiguredRefusalNamesTheWayForward() {
        EvidenceResult withSources = new EvidenceSourceRouter(
                List.of(StubAdapter.returning("recorded-replay", result("EV-1", "x"))),
                deployment(Map.of()), CLOCK)
                .collect(WORKSPACE_ID, request("EV-1", "log_count"), incident("ACME"));

        assertThat(withSources.summary())
                .contains("ACME")
                .contains("log_count")
                .contains("PUT /api/v1/troubleshooting/evidence/routes")
                .contains("recorded-replay");

        EvidenceResult noSources = new EvidenceSourceRouter(
                List.of(), deployment(Map.of()), CLOCK)
                .collect(WORKSPACE_ID, request("EV-1", "log_count"), incident("ACME"));

        assertThat(noSources.summary())
                .as("一台什么源都没启用的部署，下一步不是去配路由")
                .contains("no evidence source is enabled")
                .doesNotContain("PUT /api/v1/troubleshooting/evidence/routes");
    }

    private EvidenceProperties deployment(Map<String, Map<String, List<String>>> routes) {
        EvidenceProperties properties = new EvidenceProperties();
        properties.setRoutes(routes);
        return properties;
    }

    private EvidenceSourceRouter router(
            Map<String, Map<String, List<String>>> routes,
            EvidenceSourceAdapter... adapters) {
        return new EvidenceSourceRouter(List.of(adapters), deployment(routes), CLOCK);
    }

    private EvidenceRequest request(String requestId, String signalKind) {
        return new EvidenceRequest(
                requestId, signalKind, "collect evidence",
                Map.of("service", "order-svc", "error_code", "903001"), "-15m", true);
    }

    private IncidentContext incident(String system) {
        return new IncidentContext(
                "inc-1", system, "order-svc", "903001", "订单创建超时",
                "P0", "订单创建成功率下降", "7f3a91c", NOW, "21:18",
                "alert_webhook", IncidentCompleteness.STRUCTURED, "code=903001");
    }

    private static EvidenceResult result(String queryId, String source) {
        return new EvidenceResult(
                queryId, "L", "query", EvidenceStatus.NORMAL, "collected",
                Map.of("count", 1), source, NOW);
    }

    private static EvidenceResult missing(String queryId, String source) {
        return new EvidenceResult(
                queryId, "UNKNOWN", "", EvidenceStatus.MISSING, "unavailable",
                Map.of(), source, NOW);
    }

    private static final class StubAdapter implements EvidenceSourceAdapter {
        private final String platform;
        private final EvidenceResult result;
        private final boolean throwsOnCollect;
        private final boolean supportsSignal;
        private final AtomicInteger calls = new AtomicInteger();

        private StubAdapter(
                String platform,
                EvidenceResult result,
                boolean throwsOnCollect,
                boolean supportsSignal) {
            this.platform = platform;
            this.result = result;
            this.throwsOnCollect = throwsOnCollect;
            this.supportsSignal = supportsSignal;
        }

        static StubAdapter returning(String platform, EvidenceResult result) {
            return new StubAdapter(platform, result, false, true);
        }

        static StubAdapter throwing(String platform) {
            return new StubAdapter(platform, null, true, true);
        }

        static StubAdapter unsupported(String platform) {
            return new StubAdapter(platform, null, false, false);
        }

        int calls() {
            return calls.get();
        }

        @Override
        public String platform() {
            return platform;
        }

        @Override
        public boolean supports(String signalKind) {
            return supportsSignal;
        }

        @Override
        public EvidenceResult collect(
                long workspaceId,
                EvidenceRequest request,
                IncidentContext incident) {
            calls.incrementAndGet();
            if (throwsOnCollect) {
                throw new IllegalStateException("source is down");
            }
            return result;
        }

        @Override
        public EvidenceSourceHealth health() {
            return new EvidenceSourceHealth(
                    platform, EvidenceSourceHealth.Status.READY, false, "test adapter");
        }
    }
}
