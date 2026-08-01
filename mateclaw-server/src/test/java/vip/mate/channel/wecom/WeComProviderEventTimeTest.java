package vip.mate.channel.wecom;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeComProviderEventTimeTest {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant FALLBACK = Instant.parse("2026-07-29T03:00:00Z");

    @Test
    void parsesProviderMillisecondsInsteadOfUsingCallbackArrivalTime() {
        Instant sentAt = Instant.parse("2026-07-29T02:04:05.123Z");

        LocalDateTime parsed = WeComChannelAdapter.providerEventTime(
                sentAt.toEpochMilli(), FALLBACK);

        assertEquals(LocalDateTime.ofInstant(sentAt, BUSINESS_ZONE), parsed);
    }

    @Test
    void acceptsNumericStringsAndEpochSeconds() {
        Instant sentAt = Instant.parse("2026-07-29T02:04:05Z");

        assertEquals(
                LocalDateTime.ofInstant(sentAt, BUSINESS_ZONE),
                WeComChannelAdapter.providerEventTime(
                        Long.toString(sentAt.getEpochSecond()), FALLBACK));
    }

    @Test
    void malformedOrImplausibleProviderTimeFallsBackToReceiptTime() {
        LocalDateTime expected = LocalDateTime.ofInstant(FALLBACK, BUSINESS_ZONE);

        assertEquals(expected, WeComChannelAdapter.providerEventTime("not-a-time", FALLBACK));
        assertEquals(expected, WeComChannelAdapter.providerEventTime(0L, FALLBACK));
        assertEquals(expected, WeComChannelAdapter.providerEventTime(
                FALLBACK.plusSeconds(600).toEpochMilli(), FALLBACK));
    }
}
