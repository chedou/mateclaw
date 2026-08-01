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

        assertThat(router.canRoute("csdp", "LOG_SEARCH", "RECORDED-REPLAY"))
                .isTrue();
        assertThat(router.canRoute("CSDP", "log_trace_bundle", "recorded-replay"))
                .isFalse();
        assertThat(router.canRoute("another-system", "log_search", "recorded-replay"))
                .isFalse();
        assertThat(router.canRoute("CSDP", "log_search", "guance"))
                .isFalse();

        StubAdapter unsupported = StubAdapter.unsupported("recorded-replay");
        EvidenceSourceRouter unsupportedRouter = router(
                Map.of("CSDP", Map.of(
                        "log_search", List.of("recorded-replay"))),
                unsupported);
        assertThat(unsupportedRouter.canRoute(
                "CSDP", "log_search", "recorded-replay"))
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

    private EvidenceSourceRouter router(
            Map<String, Map<String, List<String>>> routes,
            EvidenceSourceAdapter... adapters) {
        EvidenceProperties properties = new EvidenceProperties();
        properties.setRoutes(routes);
        return new EvidenceSourceRouter(List.of(adapters), properties, CLOCK);
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
