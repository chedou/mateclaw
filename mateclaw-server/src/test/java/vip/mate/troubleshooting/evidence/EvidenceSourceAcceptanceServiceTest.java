package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.TroubleshootingSourceAcceptanceEntity;
import vip.mate.troubleshooting.repository.TroubleshootingSourceAcceptanceMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验收这件事只有两种失败方式值得防：**替人签字**，和**替系统作证**。
 *
 * <p>前者是清单没填全也放行；后者是提交方自己声称「验证过了」而服务端从没看过。
 * 下面大多数用例针对这两件事。</p>
 */
class EvidenceSourceAcceptanceServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T09:00:00Z");
    private static final EvidenceSourceAcceptance.Checklist ALL_AFFIRMED =
            new EvidenceSourceAcceptance.Checklist(true, true, true, true, true);

    private final TroubleshootingSourceAcceptanceMapper mapper =
            mock(TroubleshootingSourceAcceptanceMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("绑定齐备但没人验收过 → NOT_ACCEPTED，且不放行真源采样")
    void aUsableButUnacceptedBindingIsNotAuthorized() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.selectCount(any())).thenReturn(0L);

        EvidenceSourceAcceptanceView view = service(readyPrometheus()).inspect(7L, "prometheus");

        assertThat(view.status()).isEqualTo(EvidenceSourceAcceptanceView.Status.NOT_ACCEPTED);
        assertThat(view.acceptedForCurrentBinding()).isFalse();
        assertThat(view.currentBindingFingerprint()).isNotNull();
    }

    /**
     * 这是这张表存在的理由。配置一改指纹就变，旧行自然对不上——**没有「记得作废」
     * 这一步，也就没有忘记作废这种可能**。
     */
    @Test
    @DisplayName("验收之后改了配置 → STALE，而不是继续算已验收")
    void changingTheConfigurationAfterAcceptanceReadsAsStaleNotAccepted() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.selectCount(any())).thenReturn(1L);

        EvidenceSourceAcceptanceView view = service(readyPrometheus()).inspect(7L, "prometheus");

        assertThat(view.status()).isEqualTo(EvidenceSourceAcceptanceView.Status.STALE);
        assertThat(view.acceptedForCurrentBinding())
                .as("STALE 绝不能放行——那等于让一次针对旧配置的验收替新配置背书")
                .isFalse();
        assertThat(view.blockers()).anyMatch(item -> item.contains("配置在上次验收之后变过"));
    }

    @Test
    @DisplayName("绑定不完整时 BLOCKED——还谈不到验收")
    void anIncompleteBindingCannotBeAccepted() {
        EvidenceSourceAcceptanceView view = service(
                new PrometheusEvidenceAdapter(null, transport(true), objectMapper, clock()))
                .inspect(7L, "prometheus");

        assertThat(view.status()).isEqualTo(EvidenceSourceAcceptanceView.Status.BLOCKED);
        assertThat(view.currentBindingFingerprint()).isNull();
    }

    /**
     * 替系统作证：只要一次「我确认过了」就能落一条记录，而服务端从没看过。
     * 服务端必须自己再跑一次，取不到就拒绝。
     */
    @Test
    @DisplayName("服务端自己取不到证据时拒绝验收——「我确认过了」不能替代它")
    void acceptanceIsRefusedWhenTheServerItselfCannotObserveTheSource() {
        EvidenceSourceAcceptanceService service = service(
                new PrometheusEvidenceAdapter(
                        binding(), transport(false), objectMapper, clock()));

        assertThatThrownBy(() -> service.accept(7L, "prometheus", ALL_AFFIRMED, "owner"))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode())
                        .isEqualTo(409))
                .hasMessageContaining("refused");

        verify(mapper, never()).insert(any(TroubleshootingSourceAcceptanceEntity.class));
    }

    @Test
    @DisplayName("清单有一项没确认就拒收——半份清单不是验收")
    void aPartiallyAffirmedChecklistIsRefused() {
        EvidenceSourceAcceptanceService service = service(readyPrometheus());
        var halfSigned = new EvidenceSourceAcceptance.Checklist(true, true, true, true, false);

        assertThatThrownBy(() -> service.accept(7L, "prometheus", halfSigned, "owner"))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode())
                        .isEqualTo(400));
        verify(mapper, never()).insert(any(TroubleshootingSourceAcceptanceEntity.class));
    }

    @Test
    @DisplayName("没有鉴权身份就不能验收——验收是有名有姓的行为")
    void acceptanceRequiresAnAuthenticatedOwner() {
        assertThatThrownBy(() -> service(readyPrometheus())
                .accept(7L, "prometheus", ALL_AFFIRMED, "  "))
                .isInstanceOf(MateClawException.class);
        verify(mapper, never()).insert(any(TroubleshootingSourceAcceptanceEntity.class));
    }

    @Test
    @DisplayName("验收成功后落库：只存指纹、清单与服务端观察到的结构化事实")
    void aSuccessfulAcceptanceStoresOnlyFingerprintChecklistAndObservedFacts() {
        List<TroubleshootingSourceAcceptanceEntity> inserted = new ArrayList<>();
        when(mapper.insert(any(TroubleshootingSourceAcceptanceEntity.class))).thenAnswer(call -> {
            inserted.add(call.getArgument(0));
            return 1;
        });

        EvidenceSourceAcceptanceView view = service(readyPrometheus())
                .accept(7L, "prometheus", ALL_AFFIRMED, "owner-alice");

        assertThat(view.status()).isEqualTo(EvidenceSourceAcceptanceView.Status.ACCEPTED);
        assertThat(view.acceptedForCurrentBinding()).isTrue();
        assertThat(inserted).hasSize(1);
        String json = inserted.getFirst().getAggregateJson();
        assertThat(json)
                .contains("owner-alice")
                .as("凭据、端点、查询文本都不该进验收记录")
                .doesNotContain("token").doesNotContain("prom.internal")
                .doesNotContain("pg_stat_activity_count");
        assertThat(view.acceptance().observed().canonicalFieldsObserved())
                .as("必须记下服务端自己看到了几个字段，而不是提交方说的")
                .isPositive();
    }

    /**
     * 契约层面堵死「状态说 ACCEPTED、指纹却对不上」——那正是一次针对旧配置的
     * 验收替新配置背书的样子。
     */
    @Test
    @DisplayName("ACCEPTED 与当前指纹对不上时，契约本身拒绝构造")
    void anAcceptedViewCannotCarryAMismatchedFingerprint() {
        assertThatThrownBy(() -> new EvidenceSourceAcceptanceView(
                EvidenceSourceAcceptanceView.Status.ACCEPTED,
                "prometheus", "a".repeat(64), null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("未注册的平台不得凭空生出一个可验收状态")
    void anUnknownPlatformIsBlocked() {
        EvidenceSourceAcceptanceView view = service(readyPrometheus()).inspect(7L, "splunk");

        assertThat(view.status()).isEqualTo(EvidenceSourceAcceptanceView.Status.BLOCKED);
        assertThat(view.blockers()).anyMatch(item -> item.contains("no evidence source adapter"));
    }

    private EvidenceSourceAcceptanceService service(EvidenceSourceAdapter adapter) {
        return new EvidenceSourceAcceptanceService(
                List.of(adapter), mapper, objectMapper, clock());
    }

    private PrometheusEvidenceAdapter readyPrometheus() {
        return new PrometheusEvidenceAdapter(binding(), transport(true), objectMapper, clock());
    }

    private Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static PrometheusEvidenceAdapter.Binding binding() {
        Map<String, String> queries = new java.util.LinkedHashMap<>();
        queries.put("connections_current", "sum(pg_stat_activity_count)");
        queries.put("slow_query_count", "sum(pg_slow_queries_total)");
        queries.put("reachable", "up{job=\"postgres\"}");
        queries.put("connections_available", "sum(pg_settings_max_connections)");
        queries.put("baseline_slow", "avg_over_time(pg_slow_queries_total[7d])");
        return new PrometheusEvidenceAdapter.Binding(
                URI.create("http://prom.internal:9090/"), queries, "token-A");
    }

    private static EvidenceHttpTransport transport(boolean answers) {
        return new EvidenceHttpTransport() {
            @Override
            public Response postJson(URI uri, Map<String, String> h, String b, Duration t) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Response get(URI uri, Map<String, String> h, Duration t) {
                return answers
                        ? new Response(200, """
                            {"status":"success","data":{"resultType":"vector","result":[
                              {"metric":{},"value":[1754136000,"1"]}]}}""")
                        : new Response(503, "");
            }
        };
    }
}
