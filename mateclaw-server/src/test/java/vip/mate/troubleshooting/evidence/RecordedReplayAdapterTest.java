package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecordedReplayAdapterTest {

    private static final long WORKSPACE_ID = 1L;
    private static final Instant NOW = Instant.parse("2026-07-25T09:12:03Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void replaysAnExactSanitizedRecordAsCanonicalEvidence() {
        RecordedReplayAdapter adapter = adapter("""
                {
                  "version": 1,
                  "records": [{
                    "system": "CSDP",
                    "errorCode": "903001",
                    "service": "order-svc",
                    "requestId": "EV-1",
                    "signalKind": "log_count",
                    "namespace": "L",
                    "query": "recorded:log-count",
                    "status": "ANOMALY",
                    "summary": "错误码日志计数",
                    "observed": {"count": 148, "trace_id": "synthetic-trace-001"},
                    "source": "recorded-replay:903001",
                    "collectedAt": "2026-07-20T09:12:03Z"
                  }]
                }
                """);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request("EV-1"), incident("903001"));

        assertThat(result.status()).isEqualTo(EvidenceStatus.ANOMALY);
        assertThat(result.observed())
                .containsEntry("count", 148)
                .containsEntry("trace_id", "synthetic-trace-001");
        assertThat(result.source()).isEqualTo("recorded-replay:903001");
        assertThat(adapter.health().status()).isEqualTo(EvidenceSourceHealth.Status.READY);
        assertThat(adapter.health().verified()).isFalse();
    }

    @Test
    void refusesToReplayARecordForADifferentIncidentKey() {
        RecordedReplayAdapter adapter = adapter("""
                {"version":1,"records":[]}
                """);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID, request("EV-1"), incident("999999"));

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.source()).isEqualTo("recorded-replay:missing");
    }

    @Test
    void aMalformedCatalogDegradesWithoutBreakingCollection() {
        List<String> malformedCatalogs = List.of(
                "not-json",
                """
                {
                  "version": 1,
                  "records": [{
                    "system": "CSDP",
                    "errorCode": "903001",
                    "service": "order-svc",
                    "requestId": "EV-1",
                    "signalKind": "log_count",
                    "namespace": "L",
                    "status": "ANOMALY",
                    "observed": {"count": "148"}
                  }]
                }
                """);

        for (String catalog : malformedCatalogs) {
            RecordedReplayAdapter adapter = adapter(catalog);
            EvidenceResult result = adapter.collect(
                    WORKSPACE_ID, request("EV-1"), incident("903001"));

            assertThat(adapter.health().status()).isEqualTo(EvidenceSourceHealth.Status.DEGRADED);
            assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
            assertThat(result.collectedAt()).isEqualTo(NOW);
        }
    }

    @Test
    void replaysABoundedLogBundleForAnIncidentWithoutAnErrorCode() {
        RecordedReplayAdapter adapter = adapter("""
                {
                  "version": 1,
                  "records": [{
                    "system": "CSDP",
                    "service": "csdp-session-service",
                    "requestId": "EV-P6-2",
                    "signalKind": "log_trace_bundle",
                    "namespace": "L",
                    "query": "recorded:message-send-failed/trace-bundle",
                    "status": "ANOMALY",
                    "summary": "脱敏回放：PS ID 全链路日志包",
                    "observed": {
                      "ps_id": "synthetic-ps-message-send-001",
                      "entries": [{
                        "timestamp": 1753434723000,
                        "service": "session-api",
                        "level": "ERROR",
                        "message": "message send failed"
                      }]
                    },
                    "source": "recorded-replay:message-send-failed",
                    "collectedAt": "2026-07-20T09:12:03Z"
                  }]
                }
                """);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID,
                request("EV-P6-2", "log_trace_bundle"),
                incident("csdp-session-service", null));

        assertThat(adapter.health().status()).isEqualTo(EvidenceSourceHealth.Status.READY);
        assertThat(result.status()).isEqualTo(EvidenceStatus.ANOMALY);
        assertThat(result.observed())
                .containsEntry("ps_id", "synthetic-ps-message-send-001")
                .containsKey("entries");
    }

    @Test
    void refusesToReplayALogBundleForADifferentRequestedPsId() {
        RecordedReplayAdapter adapter = adapter("""
                {
                  "version": 1,
                  "records": [{
                    "system": "CSDP",
                    "service": "csdp-session-service",
                    "requestId": "EV-P6-2",
                    "signalKind": "log_trace_bundle",
                    "namespace": "L",
                    "status": "ANOMALY",
                    "observed": {
                      "ps_id": "recorded-ps",
                      "entries": [{
                        "timestamp": 1753434723000,
                        "service": "session-api",
                        "level": "ERROR",
                        "message": "message send failed"
                      }]
                    }
                  }]
                }
                """);
        EvidenceRequest request = new EvidenceRequest(
                "EV-P6-2", "log_trace_bundle", "confirm",
                Map.of("ps_id", "requested-ps"), "-15m", true);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID,
                request,
                incident("csdp-session-service", null));

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.source()).isEqualTo("recorded-replay:missing");
    }

    @Test
    void bundledCatalogContainsThe903001AndP6CanonicalSignalKinds() {
        EvidenceProperties.RecordedReplay config = new EvidenceProperties.RecordedReplay();
        config.setEnabled(true);
        RecordedReplayAdapter adapter = new RecordedReplayAdapter(
                config,
                new ObjectMapper(),
                new ClassPathResource("troubleshooting/evidence/recorded-replay-903001.json"),
                CLOCK);

        assertThat(adapter.health().status()).isEqualTo(EvidenceSourceHealth.Status.READY);
        assertThat(adapter.supports("log_count")).isTrue();
        assertThat(adapter.supports("metric")).isTrue();
        assertThat(adapter.supports("trace")).isTrue();
        assertThat(adapter.supports("log_search")).isTrue();
        assertThat(adapter.supports("log_trace_bundle")).isTrue();
        assertThat(adapter.supports("contrast_sample")).isTrue();
        assertThat(adapter.collect(
                WORKSPACE_ID, request("EV-1"), incident("903001")).observed())
                .containsEntry("count", 148);
        assertThat(adapter.collect(
                WORKSPACE_ID,
                request("SYNTH-LOG-SEARCH", "log_search"),
                incident("csdp-session-service", null)).observed())
                .containsEntry("ps_id", "synthetic-ps-message-send-001");
        assertThat(adapter.collect(
                WORKSPACE_ID,
                request("SYNTH-CONTRAST-SAMPLE", "contrast_sample"),
                incident("csdp-session-service", null)).observed())
                .containsEntry("failure_match_count", 92)
                .containsEntry("success_match_count", 3);
    }

    @Test
    void bundledP6SearchReplayRequiresTheRecordedSearchTerm() {
        EvidenceProperties.RecordedReplay config = new EvidenceProperties.RecordedReplay();
        config.setEnabled(true);
        RecordedReplayAdapter adapter = new RecordedReplayAdapter(
                config,
                new ObjectMapper(),
                new ClassPathResource("troubleshooting/evidence/recorded-replay-903001.json"),
                CLOCK);
        EvidenceRequest wrongKeyword = new EvidenceRequest(
                "SYNTH-LOG-SEARCH",
                "log_search",
                "sample another scenario",
                Map.of("search_term", "unrelated_safe_keyword"),
                "-15m",
                true);

        EvidenceResult result = adapter.collect(
                WORKSPACE_ID,
                wrongKeyword,
                incident("csdp-session-service", null));

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.observed()).isEmpty();
    }

    private RecordedReplayAdapter adapter(String json) {
        EvidenceProperties.RecordedReplay config = new EvidenceProperties.RecordedReplay();
        config.setEnabled(true);
        return new RecordedReplayAdapter(
                config,
                new ObjectMapper(),
                new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8)),
                CLOCK);
    }

    private EvidenceRequest request(String requestId) {
        return request(requestId, "log_count");
    }

    private EvidenceRequest request(String requestId, String signalKind) {
        Map<String, Object> target = switch (signalKind) {
            case "log_search" -> Map.of("search_term", "message_send_failed");
            case "log_trace_bundle" -> Map.of("ps_id", "synthetic-ps-message-send-001");
            case "contrast_sample" -> Map.of(
                    "scenario_key", "message_send_failed",
                    "exclude_ps_id", "synthetic-ps-message-send-001");
            default -> Map.of("service", "order-svc", "error_code", "903001");
        };
        return new EvidenceRequest(
                requestId, signalKind, "confirm",
                target, "-15m", true);
    }

    private IncidentContext incident(String errorCode) {
        return incident("order-svc", errorCode);
    }

    private IncidentContext incident(String service, String errorCode) {
        return new IncidentContext(
                "inc-1", "CSDP", service, errorCode, "订单创建超时",
                "P0", "订单创建成功率下降", "7f3a91c", NOW, "21:18",
                "alert_webhook", IncidentCompleteness.STRUCTURED, "code=" + errorCode);
    }
}
