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

        EvidenceResult result = adapter.collect(request("EV-1"), incident("903001"));

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

        EvidenceResult result = adapter.collect(request("EV-1"), incident("999999"));

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
            EvidenceResult result = adapter.collect(request("EV-1"), incident("903001"));

            assertThat(adapter.health().status()).isEqualTo(EvidenceSourceHealth.Status.DEGRADED);
            assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
            assertThat(result.collectedAt()).isEqualTo(NOW);
        }
    }

    @Test
    void bundled903001CatalogContainsAllThreeCanonicalSignalKinds() {
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
        assertThat(adapter.collect(request("EV-1"), incident("903001")).observed())
                .containsEntry("count", 148);
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
        return new EvidenceRequest(
                requestId, "log_count", "confirm",
                Map.of("service", "order-svc", "error_code", "903001"), "-15m", true);
    }

    private IncidentContext incident(String errorCode) {
        return new IncidentContext(
                "inc-1", "CSDP", "order-svc", errorCode, "订单创建超时",
                "P0", "订单创建成功率下降", "7f3a91c", NOW, "21:18",
                "alert_webhook", IncidentCompleteness.STRUCTURED, "code=" + errorCode);
    }
}
