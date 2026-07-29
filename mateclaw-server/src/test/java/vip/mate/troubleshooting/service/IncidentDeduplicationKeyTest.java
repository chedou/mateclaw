package vip.mate.troubleshooting.service;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentDeduplicationKeyTest {

    @Test
    void keyNormalizesSystemAndServicePreservesErrorCodeAndUsesFiveMinuteBucket() {
        Instant receivedAt = Instant.parse("2026-07-25T01:04:59Z");

        String first = IncidentDeduplicationKey.create(
                incident(" CSDP ", " 903001 ", " CSDP-WeChat ", null), false, receivedAt).orElseThrow();
        String equivalent = IncidentDeduplicationKey.create(
                incident("csdp", "903001", "csdp-wechat", null), false,
                Instant.parse("2026-07-25T01:00:01Z")).orElseThrow();
        String nextBucket = IncidentDeduplicationKey.create(
                incident("csdp", "903001", "csdp-wechat", null), false,
                Instant.parse("2026-07-25T01:05:00Z")).orElseThrow();
        String upperCode = IncidentDeduplicationKey.create(
                incident("csdp", "ERR-A", "csdp-wechat", null), false, receivedAt).orElseThrow();
        String lowerCode = IncidentDeduplicationKey.create(
                incident("csdp", "err-a", "csdp-wechat", null), false, receivedAt).orElseThrow();

        assertEquals(first, equivalent);
        assertNotEquals(first, nextBucket);
        assertNotEquals(upperCode, lowerCode);
        assertEquals(
                "95c5a7c0cab4aaca50b70eb1a588bd52da75436b6fb5d3c06c6644f2bfed6662",
                first,
                "error-code keys must remain byte-compatible across rolling deployments");
        assertEquals(64, first.length());
    }

    @Test
    void occurredAtWinsOverIngestionTime() {
        IncidentContext context = incident(
                "csdp", "903001", "csdp-wechat", Instant.parse("2026-07-25T01:02:00Z"));

        String first = IncidentDeduplicationKey.create(
                context, false, Instant.parse("2026-07-25T02:00:00Z")).orElseThrow();
        String laterIngestion = IncidentDeduplicationKey.create(
                context, false, Instant.parse("2026-07-25T03:00:00Z")).orElseThrow();

        assertEquals(first, laterIngestion);
    }

    @Test
    void rehearsalsAreNeverDeduplicated() {
        assertTrue(IncidentDeduplicationKey.create(
                incident("csdp", "903001", "csdp-wechat", null), true, Instant.now()).isEmpty());
    }

    @Test
    void symptomOnlyRetriesUseAStableFiveMinuteKeyWithoutMergingDifferentSymptoms() {
        Instant receivedAt = Instant.parse("2026-07-25T01:04:59Z");

        String first = IncidentDeduplicationKey.create(
                incident(" CSDP ", null, " CSDP-WeChat ", null,
                        " 会话消息发送失败 ", " trace-1 "),
                false, receivedAt).orElseThrow();
        String equivalent = IncidentDeduplicationKey.create(
                incident("csdp", null, "csdp-wechat", null,
                        "会话消息发送失败", "trace-1"),
                false, Instant.parse("2026-07-25T01:00:01Z")).orElseThrow();
        String differentSymptom = IncidentDeduplicationKey.create(
                incident("csdp", null, "csdp-wechat", null,
                        "会话列表加载失败", "trace-1"),
                false, receivedAt).orElseThrow();
        String nextBucket = IncidentDeduplicationKey.create(
                incident("csdp", null, "csdp-wechat", null,
                        "会话消息发送失败", "trace-1"),
                false, Instant.parse("2026-07-25T01:05:00Z")).orElseThrow();

        assertEquals(first, equivalent);
        assertNotEquals(first, differentSymptom);
        assertNotEquals(first, nextBucket);
        assertEquals(64, first.length());
    }

    private IncidentContext incident(
            String system,
            String errorCode,
            String service,
            Instant occurredAt) {
        return incident(system, errorCode, service, occurredAt, "title", null);
    }

    private IncidentContext incident(
            String system,
            String errorCode,
            String service,
            Instant occurredAt,
            String title,
            String traceId) {
        return new IncidentContext(
                "inc-1",
                system,
                service,
                errorCode,
                title,
                "P2",
                "待确认",
                traceId,
                occurredAt,
                null,
                "manual",
                IncidentCompleteness.STRUCTURED,
                null);
    }
}
