package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 一个取证适配器最重要的性质不是「能取到」，是**取不到的时候不编**。
 *
 * <p>编出来的 0 会让判据求值成功、规则命中、结论产出——而那个结论没有任何观测
 * 支撑，页面上还看不出来。缺一条证据只会让系统弃权。两种错误的代价差着量级，
 * 所以下面大多数用例走的是失败分支。</p>
 */
class PrometheusEvidenceAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-02T12:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecordingTransport transport = new RecordingTransport();

    @Test
    @DisplayName("正常返回：只搬运观测值，不替判据判断异常与否")
    void itCarriesObservedValuesWithoutJudgingThem() {
        EvidenceResult result = adapter(binding()).collect(7L, request(), incident());

        assertThat(result.status())
                .as("异常与否是 Playbook 判据的事，适配器说了不算")
                .isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.observed())
                .containsEntry("connections_current", 91.0d)
                .containsEntry("slow_query_count", 7.0d)
                .as("声明为布尔的字段要按声明转换，而不是塞一个 1.0 进去")
                .containsEntry("reachable", true);
        assertThat(result.source()).isEqualTo("prometheus");
        assertThat(transport.calls).hasSize(5);
        assertThat(transport.calls)
                .as("只读：只发 GET /api/v1/query")
                .allMatch(call -> call.startsWith("GET ") && call.contains("/api/v1/query?query="));
    }

    /**
     * The one that matters most. Half a metric set lets a criterion evaluate to
     * a conclusion nobody observed.
     */
    @Test
    @DisplayName("有一个字段取不到，整条判 MISSING——不补 0，不给半份指标")
    void oneUnavailableFieldMakesTheWholeResultMissing() {
        // 按 PromQL 精确让 slow_query_count 那条返回空 series，
        // 不靠调用顺序——顺序一变，这条断言就会悄悄测到别的东西
        EvidenceHttpTransport oneBlind = new EvidenceHttpTransport() {
            @Override
            public Response postJson(URI uri, Map<String, String> h, String b, Duration t) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Response get(URI uri, Map<String, String> h, Duration t) {
                return uri.toString().contains("pg_slow_queries_total")
                        ? new Response(200,
                                "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\","
                                        + "\"result\":[]}}")
                        : new Response(200, ok("1"));
            }
        };
        EvidenceResult result = new PrometheusEvidenceAdapter(
                binding(), oneBlind, objectMapper, fixedClock())
                .collect(7L, request(), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.observed())
                .as("MISSING 就必须是空的；留半份会被下游当成取到了")
                .isEmpty();
        assertThat(result.summary())
                .as("要说清是哪个字段没取到，否则运维只知道「失败了」")
                .contains("slow_query_count");
    }

    @Test
    @DisplayName("非 200、非 success、结构不符、非有限数——全部 MISSING")
    void everyUnexpectedShapeFailsClosed() {
        assertThat(collectWith(new RecordingTransport(401, ok("1"))).status())
                .isEqualTo(EvidenceStatus.MISSING);
        assertThat(collectWith(new RecordingTransport(200,
                """
                {"status":"error","errorType":"bad_data"}""")).status())
                .isEqualTo(EvidenceStatus.MISSING);
        assertThat(collectWith(new RecordingTransport(200, "not json at all")).status())
                .isEqualTo(EvidenceStatus.MISSING);
        assertThat(collectWith(new RecordingTransport(200, ok("NaN"))).status())
                .as("NaN 不是一个观测值")
                .isEqualTo(EvidenceStatus.MISSING);
        assertThat(collectWith(new RecordingTransport(200, ok("+Inf"))).status())
                .isEqualTo(EvidenceStatus.MISSING);
    }

    /**
     * 两条 series 意味着这条查询没有唯一确定一个东西。默默取第一条，等于回答了
     * 一个没有人问过的问题。
     */
    @Test
    @DisplayName("返回多条 series 时拒绝，而不是取第一条")
    void anAmbiguousQueryIsRefusedRatherThanSilentlyNarrowed() {
        String twoSeries = """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"pod":"a"},"value":[1754136000,"91"]},
                  {"metric":{"pod":"b"},"value":[1754136000,"12"]}]}}""";

        assertThat(collectWith(new RecordingTransport(200, twoSeries)).status())
                .isEqualTo(EvidenceStatus.MISSING);
    }

    @Test
    @DisplayName("网络异常不得冒泡成 500，只能是 MISSING")
    void aTransportFailureBecomesMissingRatherThanAnError() {
        EvidenceHttpTransport exploding = new EvidenceHttpTransport() {
            @Override
            public Response postJson(URI uri, Map<String, String> h, String b, Duration t) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Response get(URI uri, Map<String, String> h, Duration t) {
                throw new IllegalStateException("connection refused");
            }
        };
        EvidenceResult result = new PrometheusEvidenceAdapter(
                binding(), exploding, objectMapper, fixedClock())
                .collect(7L, request(), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
    }

    @Test
    @DisplayName("绑定不完整时不声称就绪，且明确不声称已验证")
    void anIncompleteBindingIsNeverReportedAsReadyOrVerified() {
        assertThat(adapter(null).health().status())
                .isEqualTo(EvidenceSourceHealth.Status.DISABLED);

        // 只映射了一部分 canonical 字段 → 永远产不出合法结果，
        // 必须是 DEGRADED，而不是 READY 之后每次都 MISSING
        var partial = new PrometheusEvidenceAdapter.Binding(
                URI.create("http://prom.internal:9090"),
                Map.of("connections_current", "up"), null);
        assertThat(adapter(partial).health().status())
                .as("部分映射不能自称就绪")
                .isEqualTo(EvidenceSourceHealth.Status.DEGRADED);
        assertThat(adapter(partial).supports("metric")).isFalse();

        var unknownField = new PrometheusEvidenceAdapter.Binding(
                URI.create("http://prom.internal:9090"),
                Map.of("cpu_percent", "up"), null);
        assertThat(adapter(unknownField).supports("metric"))
                .as("canonical 契约里没有这个字段")
                .isFalse();

        assertThat(adapter(binding()).health().verified())
                .as("端点可达不等于已验证；只有 owner 验收才能声称后者")
                .isFalse();
    }

    @Test
    @DisplayName("只认领 metric，不越界宣称支持别的信号")
    void itClaimsOnlyTheSignalKindItActuallyServes() {
        PrometheusEvidenceAdapter adapter = adapter(binding());
        assertThat(adapter.supports("metric")).isTrue();
        assertThat(adapter.supports("log_search")).isFalse();
        assertThat(adapter.supports("log_trace_bundle")).isFalse();
        assertThat(PrometheusEvidenceAdapter.canonicalFields())
                .contains("connections_current", "slow_query_count");
    }


    /**
     * 指纹的价值全在「什么会让它变、什么不会」上。定错了，验收要么形同虚设
     * （改了配置还认旧验收），要么天天作废（轮换个凭据就要重来，逼人把重新验收
     * 当成走过场——那比不验收更糟）。
     */
    @Test
    @DisplayName("端点或查询变了，指纹必须变——旧验收自动失效")
    void changingWhatIsQueriedChangesTheFingerprint() {
        String base = adapter(binding()).bindingFingerprint();
        assertThat(base).as("可用绑定必须能算出指纹").isNotNull().hasSize(64);
        Map<String, String> moved = new java.util.LinkedHashMap<>(binding().fieldQueries());
        moved.put("slow_query_count", "sum(other_slow_total)");
        assertThat(adapter(new PrometheusEvidenceAdapter.Binding(
                URI.create("http://prom.internal:9090/"), moved, null)).bindingFingerprint())
                .as("换了一条 PromQL，验收的就不是同一件事了")
                .isNotEqualTo(base);

        assertThat(adapter(new PrometheusEvidenceAdapter.Binding(
                URI.create("http://other.internal:9090/"), binding().fieldQueries(), null))
                .bindingFingerprint())
                .as("换了端点同理")
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("轮换凭据不改变指纹，但从匿名改成带鉴权会改变")
    void rotatingACredentialDoesNotInvalidateAcceptanceButChangingTheAuthPathDoes() {
        var anon = binding();
        var withToken = new PrometheusEvidenceAdapter.Binding(
                anon.endpoint(), anon.fieldQueries(), "token-A");
        var rotated = new PrometheusEvidenceAdapter.Binding(
                anon.endpoint(), anon.fieldQueries(), "token-B");

        assertThat(adapter(rotated).bindingFingerprint())
                .as("轮换凭据查的还是同一个地方、同一批查询")
                .isEqualTo(adapter(withToken).bindingFingerprint());
        assertThat(adapter(withToken).bindingFingerprint())
                .as("从匿名改成带鉴权，换掉的是授权路径")
                .isNotEqualTo(adapter(anon).bindingFingerprint());
        assertThat(adapter(withToken).bindingFingerprint())
                .as("指纹里不得出现凭据本身")
                .doesNotContain("token");
    }

    @Test
    @DisplayName("绑定不可用时没有指纹——没有东西可供验收")
    void anUnusableBindingHasNoFingerprint() {
        assertThat(adapter(null).bindingFingerprint()).isNull();
    }

    private EvidenceResult collectWith(RecordingTransport failing) {
        return new PrometheusEvidenceAdapter(
                binding(), failing, objectMapper, fixedClock())
                .collect(7L, request(), incident());
    }

    private PrometheusEvidenceAdapter adapter(PrometheusEvidenceAdapter.Binding binding) {
        return new PrometheusEvidenceAdapter(binding, transport, objectMapper, fixedClock());
    }

    private Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static PrometheusEvidenceAdapter.Binding binding() {
        // LinkedHashMap keeps the two queries in a stable order so the failing
        // one below is deterministically the second.
        // 必须映射 canonical `metric` 的全部字段：这个信号是一个整包，
        // 不是一份菜单。缺一个字段的绑定永远产不出合法结果。
        Map<String, String> queries = new java.util.LinkedHashMap<>();
        queries.put("connections_current", "sum(pg_stat_activity_count)");
        queries.put("slow_query_count", "sum(pg_slow_queries_total)");
        queries.put("reachable", "up{job=\"postgres\"}");
        queries.put("connections_available", "sum(pg_settings_max_connections)");
        queries.put("baseline_slow", "avg_over_time(pg_slow_queries_total[7d])");
        return new PrometheusEvidenceAdapter.Binding(
                URI.create("http://prom.internal:9090/"), queries, null);
    }

    private static EvidenceRequest request() {
        return new EvidenceRequest("EV-METRIC", "metric", "连接池水位",
                Map.of(), "-15m", true);
    }

    private static IncidentContext incident() {
        return new IncidentContext(
                "inc-1", "CSDP", "order-svc", "903001", "数据库访问异常", "P0",
                "所有客户", null, NOW.minusSeconds(300), null, "alert",
                IncidentCompleteness.STRUCTURED, "error_code=903001");
    }

    private static String ok(String value) {
        return """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{},"value":[1754136000,"%s"]}]}}""".formatted(value);
    }

    /** Records what was actually sent so "read-only" is checked, not assumed. */
    private static final class RecordingTransport implements EvidenceHttpTransport {
        private final List<String> calls = new ArrayList<>();
        private final int statusCode;
        private List<String> bodies;
        private int index;

        RecordingTransport() {
            this(200, ok("91"), ok("7"), ok("1"), ok("200"), ok("3"));
        }

        RecordingTransport(int statusCode, String... bodies) {
            this.statusCode = statusCode;
            this.bodies = List.of(bodies);
        }

        @Override
        public Response postJson(URI uri, Map<String, String> h, String b, Duration t) {
            throw new UnsupportedOperationException("the adapter must never POST");
        }

        @Override
        public Response get(URI uri, Map<String, String> headers, Duration timeout) {
            calls.add("GET " + uri);
            String body = bodies.get(Math.min(index++, bodies.size() - 1));
            return new Response(statusCode, body);
        }
    }
}
