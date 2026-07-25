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
    void rehearsalsAndIncidentsWithoutErrorCodeAreNeverDeduplicated() {
        assertTrue(IncidentDeduplicationKey.create(
                incident("csdp", "903001", "csdp-wechat", null), true, Instant.now()).isEmpty());
        assertTrue(IncidentDeduplicationKey.create(
                incident("csdp", null, "csdp-wechat", null), false, Instant.now()).isEmpty());
    }

    private IncidentContext incident(
            String system,
            String errorCode,
            String service,
            Instant occurredAt) {
        return new IncidentContext(
                "inc-1",
                system,
                service,
                errorCode,
                "title",
                "P2",
                "待确认",
                null,
                occurredAt,
                null,
                "manual",
                IncidentCompleteness.STRUCTURED,
                null);
    }
}
