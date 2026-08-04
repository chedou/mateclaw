package vip.mate.troubleshooting.agent;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.synthesis.DeterministicLogTraceCompressor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TroubleshootingEvidenceModelProjectorTest {

    private final TroubleshootingEvidenceModelProjector projector =
            new TroubleshootingEvidenceModelProjector(
                    new DeterministicLogTraceCompressor());

    @Test
    void projectsOnlyTheBoundedCanonicalFactsFromImportedGuanceCapabilities() {
        EvidenceResult errorScan = evidence("EV-ERROR", Map.of(
                "error_count", 12,
                "affected_trace_count", 7,
                "latest_trace_id", "trace-007"));
        EvidenceResult monitorScan = evidence("EV-MONITOR", Map.of(
                "event_count", 2,
                "latest_status", "critical",
                "latest_checker", "csdp-api-error-rate"));
        EvidenceResult workload = evidence("EV-K8S", Map.of(
                "pod_count", 3,
                "container_count", 4,
                "running_container_count", 3,
                "unhealthy_container_count", 1,
                "max_cpu_percent", 82.5,
                "max_memory_percent", 76.25));

        List<TroubleshootingEvidenceModelProjector.EvidenceDescriptor> descriptors =
                projector.project(List.of(errorScan, monitorScan, workload)).evidence();

        assertThat(descriptors)
                .extracting(TroubleshootingEvidenceModelProjector.EvidenceDescriptor::signalKind)
                .containsExactly(
                        "error_log_scan", "monitor_event_scan", "k8s_workload_health");
        assertThat(descriptors.get(0).facts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "error_count", 12,
                "affected_trace_count", 7,
                "latest_trace_id", "trace-007"));
        assertThat(descriptors.get(1).facts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "event_count", 2,
                "latest_status", "critical",
                "latest_checker", "csdp-api-error-rate"));
        assertThat(descriptors.get(2).source()).isEqualTo("guance");
        assertThat(descriptors.get(2).facts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "pod_count", 3,
                "container_count", 4,
                "running_container_count", 3,
                "unhealthy_container_count", 1,
                "max_cpu_percent", 82.5,
                "max_memory_percent", 76.25));
    }

    @Test
    void withholdsMalformedSkillEvidenceFromTheModel() {
        EvidenceResult malformed = evidence("EV-ERROR", Map.of(
                "error_count", 1,
                "affected_trace_count", 2));

        TroubleshootingEvidenceModelProjector.EvidenceDescriptor descriptor =
                projector.descriptor(malformed);

        assertThat(descriptor.signalKind()).isEqualTo("unknown");
        assertThat(descriptor.facts()).isEmpty();
    }

    private EvidenceResult evidence(String queryId, Map<String, Object> observed) {
        return new EvidenceResult(
                queryId,
                "O+M",
                "",
                EvidenceStatus.NORMAL,
                "canonical aggregate",
                observed,
                "guance:aggregate",
                Instant.parse("2026-08-04T06:00:00Z"));
    }
}
