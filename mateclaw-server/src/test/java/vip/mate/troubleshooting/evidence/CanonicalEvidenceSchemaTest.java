package vip.mate.troubleshooting.evidence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalEvidenceSchemaTest {

    @Test
    void acceptsTheTwoP6LogContracts() {
        assertThat(CanonicalEvidenceSchema.supports("log_search")).isTrue();
        assertThat(CanonicalEvidenceSchema.isValid("log_search", Map.of(
                "match_count", 4,
                "ps_id", "synthetic-ps-001",
                "sample_message", "message send failed")))
                .isTrue();

        assertThat(CanonicalEvidenceSchema.supports("log_trace_bundle")).isTrue();
        assertThat(CanonicalEvidenceSchema.isValid("log_trace_bundle", Map.of(
                "ps_id", "synthetic-ps-001",
                "entries", List.of(
                        Map.of(
                                "timestamp", 1753434723000L,
                                "service", "session-api",
                                "level", "INFO",
                                "message", "message accepted"),
                        Map.of(
                                "timestamp", 1753434723042L,
                                "service", "session-state",
                                "level", "ERROR",
                                "message", "concurrent write rejected",
                                "duration_ms", 42)))))
                .isTrue();
    }

    @Test
    void rejectsIncompleteOrUnboundedLogContracts() {
        assertThat(CanonicalEvidenceSchema.isValid("log_search", Map.of(
                "match_count", 4,
                "ps_id", "synthetic-ps-001")))
                .isFalse();
        assertThat(CanonicalEvidenceSchema.isValid("log_trace_bundle", Map.of(
                "ps_id", "synthetic-ps-001",
                "entries", List.of(Map.of(
                        "timestamp", 1753434723000L,
                        "service", "session-api",
                        "message", "missing level")))))
                .isFalse();
        assertThat(CanonicalEvidenceSchema.isValid("log_trace_bundle", Map.of(
                "ps_id", "synthetic-ps-001",
                "entries", List.of())))
                .isFalse();
    }
}
