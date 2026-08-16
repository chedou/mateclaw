package vip.mate.troubleshooting.investigation;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadOnlyToolRegistryTest {

    @Test
    void resolvesOnlyAnExplicitlyAllowedReadOnlyToolVersion() {
        StubTool tool = new StubTool("canonical-evidence", "1", Set.of("error_log_scan"));
        ReadOnlyToolRegistry registry = new ReadOnlyToolRegistry(List.of(tool));

        EvidenceResult result = registry.collect(new ReadOnlyToolRegistry.Invocation(
                "canonical-evidence",
                "1",
                1L,
                incident(),
                request("error_log_scan"),
                Set.of("canonical-evidence@1"),
                Set.of("guance"),
                Instant.MAX));

        assertThat(result.status()).isEqualTo(EvidenceStatus.ANOMALY);
        assertThat(tool.calls()).isEqualTo(1);
    }

    @Test
    void rejectsOutOfPolicyAndUnsupportedSignalsBeforeCallingTheTool() {
        StubTool tool = new StubTool("canonical-evidence", "1", Set.of("error_log_scan"));
        ReadOnlyToolRegistry registry = new ReadOnlyToolRegistry(List.of(tool));

        assertThatThrownBy(() -> registry.collect(new ReadOnlyToolRegistry.Invocation(
                "canonical-evidence", "1", 1L, incident(), request("error_log_scan"),
                Set.of("another-tool@1"), Set.of(), Instant.MAX)))
                .isInstanceOf(ReadOnlyToolRegistry.PolicyViolation.class)
                .hasMessageContaining("not allowed");
        assertThatThrownBy(() -> registry.collect(new ReadOnlyToolRegistry.Invocation(
                "canonical-evidence", "1", 1L, incident(), request("metric"),
                Set.of("canonical-evidence@1"), Set.of(), Instant.MAX)))
                .isInstanceOf(ReadOnlyToolRegistry.PolicyViolation.class)
                .hasMessageContaining("does not support");
        assertThat(tool.calls()).isZero();
    }

    @Test
    void rejectsDuplicateToolKeyAndVersionAtStartup() {
        StubTool one = new StubTool("canonical-evidence", "1", Set.of("error_log_scan"));
        StubTool duplicate = new StubTool("CANONICAL-EVIDENCE", "1", Set.of("metric"));

        assertThatThrownBy(() -> new ReadOnlyToolRegistry(List.of(one, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate read-only evidence tool");
    }

    @Test
    void malformedToolOutputFailsClosedAsMissingEvidence() {
        ReadOnlyEvidenceTool malformed = new StubTool(
                "canonical-evidence", "1", Set.of("error_log_scan")) {
            @Override
            public EvidenceResult collect(
                    ReadOnlyToolRegistry.Context context,
                    EvidenceRequest request) {
                return new EvidenceResult(
                        request.requestId(), "logs", "", EvidenceStatus.ANOMALY,
                        "malformed", Map.of("made_up", 1), "stub", Instant.EPOCH);
            }
        };
        ReadOnlyToolRegistry registry = new ReadOnlyToolRegistry(List.of(malformed));

        EvidenceResult result = registry.collect(new ReadOnlyToolRegistry.Invocation(
                "canonical-evidence", "1", 1L, incident(), request("error_log_scan"),
                Set.of("canonical-evidence@1"), Set.of(), Instant.MAX));

        assertThat(result.status()).isEqualTo(EvidenceStatus.MISSING);
        assertThat(result.observed()).isEmpty();
        assertThat(result.source()).isEqualTo("tool-registry:invalid-canonical-output");
    }

    private static EvidenceRequest request(String signalKind) {
        return new EvidenceRequest("ev-1", signalKind, "investigate", Map.of(), "-15m", true);
    }

    private static IncidentContext incident() {
        return new IncidentContext(
                "incident-1", "CSDP", "csdp-wechat", "904003", "ITGW访问失败",
                "P1", "客户受影响", null, Instant.parse("2026-08-16T10:00:00Z"),
                null, "web", IncidentCompleteness.STRUCTURED, null);
    }

    private static class StubTool implements ReadOnlyEvidenceTool {
        private final Descriptor descriptor;
        private final AtomicInteger calls = new AtomicInteger();

        private StubTool(String key, String version, Set<String> signals) {
            this.descriptor = new Descriptor(
                    key, version, Capability.READ_EVIDENCE, signals);
        }

        @Override
        public Descriptor descriptor() {
            return descriptor;
        }

        @Override
        public EvidenceResult collect(
                ReadOnlyToolRegistry.Context context,
                EvidenceRequest request) {
            calls.incrementAndGet();
            return new EvidenceResult(
                    request.requestId(), "logs", "", EvidenceStatus.ANOMALY,
                    "two errors", Map.of("error_count", 2), "stub", Instant.EPOCH);
        }

        int calls() {
            return calls.get();
        }
    }
}
