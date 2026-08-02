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
 * 日志适配器的两个要害：**串联键不许猜**，**报障文本不许进查询结构**。
 *
 * <p>猜错串联键的后果不是取不到，是把两次不相干的请求当成同一次——而下游的
 * 全链路日志包会照单全收，最后给出一条看起来完整、实则拼接自两次故障的证据链。</p>
 */
class ElasticsearchEvidenceAdapterTest {

    private static final Instant NOW = Instant.parse("2026-08-02T13:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("正常返回：命中数 + 串联键 + 脱敏样本，异常与否交给判据")
    void itReportsCountCorrelationIdAndARedactedSample() {
        RecordingTransport transport = new RecordingTransport(200, """
                {"hits":{"total":{"value":148,"relation":"eq"},
                 "hits":[{"_source":{"trace":{"id":"ps-abc-001"},
                          "message":"db connect failed token=Bearer abcdefghijklmnop"}}]}}""");

        EvidenceResult result = adapter(binding(), transport).collect(7L, request(), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.observed())
                .containsEntry("match_count", 148L)
                .as("点分路径要能读到嵌套的 trace.id")
                .containsEntry("ps_id", "ps-abc-001");
        assertThat(String.valueOf(result.observed().get("sample_message")))
                .as("样本日志必须脱敏后才离开适配器")
                .doesNotContain("abcdefghijklmnop");
    }

    /**
     * 这一条是这个适配器存在的理由。串联键取不到就必须停，因为下一步「按 PS ID
     * 取回全链路」根本无从谈起，而一条半份证据会被下游当成取到了。
     */
    @Test
    @DisplayName("配置的串联字段没有值时判 MISSING，并点名是哪个字段")
    void aMissingCorrelationValueStopsTheChainAndNamesTheField() {
        RecordingTransport transport = new RecordingTransport(200, """
                {"hits":{"total":{"value":9,"relation":"eq"},
                 "hits":[{"_source":{"message":"db connect failed"}}]}}""");

        EvidenceResult result = adapter(binding(), transport).collect(7L, request(), incident());

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.observed()).isEmpty();
        assertThat(result.summary())
                .as("要说清是哪个字段，否则运维不知道该去改什么配置")
                .contains("trace.id");
    }

    @Test
    @DisplayName("串联字段没配就整个不可用——不挑一个看起来像的")
    void anUnconfiguredCorrelationFieldDisablesTheAdapterRatherThanGuessing() {
        var noCorrelation = new ElasticsearchEvidenceAdapter.Binding(
                URI.create("http://es.internal:9200"), "app-logs-*", null, "message", null);

        ElasticsearchEvidenceAdapter adapter =
                adapter(noCorrelation, new RecordingTransport(200, "{}"));

        assertThat(adapter.supports("log_search")).isFalse();
        assertThat(adapter.health().status())
                .isEqualTo(EvidenceSourceHealth.Status.DEGRADED);
        assertThat(adapter.health().detail()).contains("correlation field");
    }

    /**
     * ES 的 query DSL 是 JSON。把报障文本拼进**结构**里等于开一个查询注入面；
     * 它只能作为 match_phrase 的**值**出现。
     */
    @Test
    @DisplayName("报障文本只作为查询的值，不进入查询结构")
    void reportedTextNeverBecomesPartOfTheQueryStructure() {
        RecordingTransport transport = new RecordingTransport(200, """
                {"hits":{"total":{"value":0,"relation":"eq"},"hits":[]}}""");
        EvidenceRequest hostile = new EvidenceRequest(
                "EV-1", "log_search", "查失败日志",
                Map.of("search_term", "\"}},\"script\":{\"source\":\"whoami\"}"),
                "-15m", true);

        adapter(binding(), transport).collect(7L, hostile, incident());

        assertThat(transport.bodies).hasSize(1);
        String sent = transport.bodies.getFirst();
        assertThat(sent)
                .as("恶意串必须是被转义的值，不能成为一个新的查询子句")
                .contains("match_phrase")
                .doesNotContain("\"script\":{\"source\"");
    }

    @Test
    @DisplayName("非 200、非 JSON、没有 total、网络异常——全部 MISSING")
    void everyUnexpectedShapeFailsClosed() {
        assertThat(collect(new RecordingTransport(403, "{}")).status())
                .isEqualTo(EvidenceStatus.MISSING);
        assertThat(collect(new RecordingTransport(200, "not json")).status())
                .isEqualTo(EvidenceStatus.MISSING);
        assertThat(collect(new RecordingTransport(200, """
                {"hits":{"hits":[]}}""")).status())
                .as("没说命中多少就不能当成 0 —— 那是编的")
                .isEqualTo(EvidenceStatus.MISSING);
    }

    @Test
    @DisplayName("兼容 ES 7 之前的裸数字 total")
    void itReadsTheOlderScalarHitTotal() {
        EvidenceResult result = collect(new RecordingTransport(200, """
                {"hits":{"total":42,
                 "hits":[{"_source":{"trace":{"id":"ps-1"},"message":"boom"}}]}}"""));

        assertThat(result.status()).isEqualTo(EvidenceStatus.NORMAL);
        assertThat(result.observed()).containsEntry("match_count", 42L);
    }

    @Test
    @DisplayName("只认领 log_search")
    void itClaimsOnlyTheSignalKindItServes() {
        ElasticsearchEvidenceAdapter adapter =
                adapter(binding(), new RecordingTransport(200, "{}"));
        assertThat(adapter.supports("log_search")).isTrue();
        assertThat(adapter.supports("metric")).isFalse();
        assertThat(adapter.supports("log_trace_bundle")).isFalse();
    }


    /**
     * 指纹的价值全在「什么会让它变、什么不会」上。定错了，验收要么形同虚设
     * （改了配置还认旧验收），要么天天作废（轮换个凭据就要重来，逼人把重新验收
     * 当成走过场——那比不验收更糟）。
     */
    @Test
    @DisplayName("端点或查询变了，指纹必须变——旧验收自动失效")
    void changingWhatIsQueriedChangesTheFingerprint() {
        String base = adapter(binding(), new RecordingTransport(200, "{}")).bindingFingerprint();
        assertThat(base).as("可用绑定必须能算出指纹").isNotNull().hasSize(64);
        assertThat(adapter(new ElasticsearchEvidenceAdapter.Binding(
                URI.create("http://es.internal:9200/"), "app-logs-*",
                "traceId", "message", null), new RecordingTransport(200, "{}"))
                .bindingFingerprint())
                .as("换了串联字段就是换了「哪两条日志算同一次请求」——最该失效的一项")
                .isNotEqualTo(base);

        assertThat(adapter(new ElasticsearchEvidenceAdapter.Binding(
                URI.create("http://es.internal:9200/"), "other-logs-*",
                "trace.id", "message", null), new RecordingTransport(200, "{}"))
                .bindingFingerprint())
                .as("换了索引同理")
                .isNotEqualTo(base);
    }

    @Test
    @DisplayName("轮换凭据不改变指纹，但从匿名改成带鉴权会改变")
    void rotatingACredentialDoesNotInvalidateAcceptanceButChangingTheAuthPathDoes() {
        var anon = binding();
        var withToken = new ElasticsearchEvidenceAdapter.Binding(
                anon.endpoint(), anon.index(), anon.correlationField(),
                anon.messageField(), "token-A");
        var rotated = new ElasticsearchEvidenceAdapter.Binding(
                anon.endpoint(), anon.index(), anon.correlationField(),
                anon.messageField(), "token-B");
        RecordingTransport idle = new RecordingTransport(200, "{}");

        assertThat(adapter(rotated, idle).bindingFingerprint())
                .isEqualTo(adapter(withToken, idle).bindingFingerprint());
        assertThat(adapter(withToken, idle).bindingFingerprint())
                .isNotEqualTo(adapter(anon, idle).bindingFingerprint());
        assertThat(adapter(withToken, idle).bindingFingerprint())
                .as("指纹里不得出现凭据本身")
                .doesNotContain("token");
    }

    @Test
    @DisplayName("绑定不可用时没有指纹——没有东西可供验收")
    void anUnusableBindingHasNoFingerprint() {
        assertThat(adapter(null, new RecordingTransport(200, "{}")).bindingFingerprint()).isNull();
    }

    private EvidenceResult collect(RecordingTransport transport) {
        return adapter(binding(), transport).collect(7L, request(), incident());
    }

    private ElasticsearchEvidenceAdapter adapter(
            ElasticsearchEvidenceAdapter.Binding binding, RecordingTransport transport) {
        return new ElasticsearchEvidenceAdapter(
                binding, transport, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ElasticsearchEvidenceAdapter.Binding binding() {
        return new ElasticsearchEvidenceAdapter.Binding(
                URI.create("http://es.internal:9200/"), "app-logs-*",
                "trace.id", "message", null);
    }

    private static EvidenceRequest request() {
        return new EvidenceRequest("EV-1", "log_search", "查失败日志",
                Map.of("search_term", "db connect failed"), "-15m", true);
    }

    private static IncidentContext incident() {
        return new IncidentContext(
                "inc-1", "CSDP", "order-svc", "903001", "数据库访问异常", "P0",
                "所有客户", null, NOW.minusSeconds(300), null, "alert",
                IncidentCompleteness.STRUCTURED, "error_code=903001");
    }

    /** Captures the request body so "the text never shapes the query" is checked. */
    private static final class RecordingTransport implements EvidenceHttpTransport {
        private final List<String> bodies = new ArrayList<>();
        private final int statusCode;
        private final String responseBody;

        RecordingTransport(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        @Override
        public Response postJson(URI uri, Map<String, String> h, String body, Duration t) {
            bodies.add(body);
            return new Response(statusCode, responseBody);
        }

        @Override
        public Response get(URI uri, Map<String, String> h, Duration t) {
            throw new UnsupportedOperationException("log search uses _search POST");
        }
    }
}
