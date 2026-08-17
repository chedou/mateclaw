package vip.mate.troubleshooting.evidence;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalEvidenceSchemaTest {

    @Test
    void acceptsACloudDialSyntheticProbeWithoutTreatingMissingDataAsHealth() {
        assertThat(CanonicalEvidenceSchema.supports("synthetic_probe")).isTrue();
        assertThat(CanonicalEvidenceSchema.isValid("synthetic_probe", Map.of(
                "status_code", 200,
                "target_url", "https://csdp-applet.sangfor.com",
                "probe_name", "客服数字化平台-首页-可用性监控")))
                .isTrue();

        assertThat(CanonicalEvidenceSchema.isValid("synthetic_probe", Map.of(
                "target_url", "https://csdp-applet.sangfor.com",
                "probe_name", "客服数字化平台-首页-可用性监控")))
                .as("a missing status code is unknown evidence, not a healthy probe")
                .isFalse();
        assertThat(CanonicalEvidenceSchema.isValid("synthetic_probe", Map.of(
                "status_code", "200",
                "target_url", "https://csdp-applet.sangfor.com",
                "probe_name", "客服数字化平台-首页-可用性监控")))
                .as("status codes must retain their canonical numeric type")
                .isFalse();
    }

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
    void acceptsARequestLevelFailurePatternBreakdownButRejectsImpossibleCounts() {
        assertThat(CanonicalEvidenceSchema.supports("cti_failure_pattern_scan")).isTrue();
        assertThat(CanonicalEvidenceSchema.isValid("cti_failure_pattern_scan", Map.of(
                "failure_request_count", 2,
                "classified_failure_request_count", 2,
                "missing_required_code_request_count", 1,
                "downstream_record_not_found_request_count", 1)))
                .isTrue();

        assertThat(CanonicalEvidenceSchema.isValid("cti_failure_pattern_scan", Map.of(
                "failure_request_count", 1,
                "classified_failure_request_count", 1,
                "missing_required_code_request_count", 1,
                "downstream_record_not_found_request_count", 1)))
                .as("one failed request may preserve two parallel clues")
                .isTrue();

        assertThat(CanonicalEvidenceSchema.isValid("cti_failure_pattern_scan", Map.of(
                "failure_request_count", 1,
                "classified_failure_request_count", 2,
                "missing_required_code_request_count", 1,
                "downstream_record_not_found_request_count", 0)))
                .as("the classified union cannot exceed the failed request cohort")
                .isFalse();

        assertThat(CanonicalEvidenceSchema.isValid("cti_failure_pattern_scan", Map.of(
                "failure_request_count", 2,
                "classified_failure_request_count", 0,
                "missing_required_code_request_count", 1,
                "downstream_record_not_found_request_count", 1)))
                .as("the union must contain every individual pattern set")
                .isFalse();
        assertThat(CanonicalEvidenceSchema.isValid("cti_failure_pattern_scan", Map.of(
                "failure_request_count", 2,
                "classified_failure_request_count", 2,
                "missing_required_code_request_count", 0,
                "downstream_record_not_found_request_count", 0)))
                .as("the union cannot contain requests absent from every pattern set")
                .isFalse();
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

    @Test
    void acceptsSanitizedErrorAlertAndK8sSkillContracts() {
        assertThat(CanonicalEvidenceSchema.isValid("error_log_scan", Map.of(
                "error_count", 12,
                "affected_trace_count", 7,
                "latest_trace_id", "trace-007"))).isTrue();
        assertThat(CanonicalEvidenceSchema.isValid("monitor_event_scan", Map.of(
                "event_count", 3,
                "latest_status", "critical",
                "latest_checker", "CSDP API error rate"))).isTrue();
        assertThat(CanonicalEvidenceSchema.isValid("k8s_workload_health", Map.of(
                "pod_count", 3,
                "container_count", 4,
                "running_container_count", 3,
                "unhealthy_container_count", 1,
                "max_cpu_percent", 82.5,
                "max_memory_percent", 76.25))).isTrue();
        assertThat(CanonicalEvidenceSchema.isValid("k8s_pod_status", Map.of(
                "pod_count", 4,
                "running_pod_count", 3,
                "non_running_pod_count", 1))).isTrue();
        assertThat(CanonicalEvidenceSchema.isValid("k8s_node_status", Map.of(
                "node_count", 2,
                "related_host_count", 2,
                "max_node_cpu_percent", 71.5,
                "max_node_memory_percent", 64.0))).isTrue();
        assertThat(CanonicalEvidenceSchema.isValid("k8s_node_status", Map.of(
                "node_count", 1,
                "related_host_count", 1))).isTrue();
        assertThat(CanonicalEvidenceSchema.isValid("host_status", Map.of(
                "host_count", 2,
                "max_host_cpu_percent", 88.0,
                "max_host_memory_percent", 73.25))).isTrue();
        assertThat(CanonicalEvidenceSchema.isValid("host_status", Map.of(
                "host_count", 1))).isTrue();
    }

    @Test
    void rejectsMalformedSkillFactsInsteadOfTreatingThemAsHealthy() {
        assertThat(CanonicalEvidenceSchema.isValid("error_log_scan", Map.of(
                "error_count", 2,
                "affected_trace_count", 3)))
                .as("distinct affected traces cannot exceed matching errors")
                .isFalse();
        assertThat(CanonicalEvidenceSchema.isValid("monitor_event_scan", Map.of(
                "event_count", -1))).isFalse();
        assertThat(CanonicalEvidenceSchema.isValid("monitor_event_scan", Map.of(
                "event_count", 2,
                "latest_status", "critical")))
                .as("a non-empty alert aggregate must retain its exact checker")
                .isFalse();
        assertThat(CanonicalEvidenceSchema.isValid("monitor_event_scan", Map.of(
                "event_count", 2,
                "latest_status", "healthy",
                "latest_checker", "CSDP API error rate")))
                .as("only the contract's warning-or-higher statuses are canonical")
                .isFalse();
        assertThat(CanonicalEvidenceSchema.isValid("monitor_event_scan", Map.of(
                "event_count", 0,
                "latest_status", "warning",
                "latest_checker", "stale checker")))
                .as("a zero aggregate must not carry stale latest-event facts")
                .isFalse();
        assertThat(CanonicalEvidenceSchema.isValid("k8s_workload_health", Map.of(
                "pod_count", 2,
                "container_count", 2,
                "running_container_count", 3,
                "unhealthy_container_count", 0,
                "max_cpu_percent", 12,
                "max_memory_percent", 20))).isFalse();
        assertThat(CanonicalEvidenceSchema.isValid("k8s_workload_health", Map.of(
                "pod_count", 2,
                "container_count", 2,
                "running_container_count", 2,
                "unhealthy_container_count", 0,
                "max_cpu_percent", -0.1,
                "max_memory_percent", 20))).isFalse();
        assertThat(CanonicalEvidenceSchema.isValid("k8s_workload_health", Map.of(
                "pod_count", 3,
                "container_count", 2,
                "running_container_count", 2,
                "unhealthy_container_count", 0,
                "max_cpu_percent", 12,
                "max_memory_percent", 20)))
                .as("a workload cannot have more pods than observed containers")
                .isFalse();
        assertThat(CanonicalEvidenceSchema.isValid("k8s_pod_status", Map.of(
                "pod_count", 2,
                "running_pod_count", 2,
                "non_running_pod_count", 1)))
                .as("running + non-running pods cannot exceed distinct pod_count")
                .isFalse();
        assertThat(CanonicalEvidenceSchema.isValid("host_status", Map.of(
                "host_count", 1,
                "max_host_cpu_percent", -1,
                "max_host_memory_percent", 10))).isFalse();
    }

    @Test
    void acceptsMeasuredImpactWithNullableCountsButRejectsUnknownOrFractionalFacts() {
        assertThat(CanonicalEvidenceSchema.isValid("incident_impact", Map.of(
                "function_scope", "消息发送功能",
                "affected_customers", 2,
                "blast_radius", "MULTI_CUSTOMER",
                "observed_at", 1753002785000L)))
                .isTrue();

        assertThat(CanonicalEvidenceSchema.isValid("incident_impact", Map.of(
                "function_scope", "消息发送功能",
                "blast_radius", "UNKNOWN",
                "observed_at", 1753002785000L)))
                .as("an all-unknown observation is not measured impact evidence")
                .isFalse();
        assertThat(CanonicalEvidenceSchema.isValid("incident_impact", Map.of(
                "function_scope", "消息发送功能",
                "affected_customers", 1.5,
                "blast_radius", "MULTI_CUSTOMER",
                "observed_at", 1753002785000L)))
                .isFalse();
        assertThat(CanonicalEvidenceSchema.isValid("incident_impact", Map.of(
                "function_scope", "消息发送功能",
                "affected_customers", 2,
                "blast_radius", "GLOBAL",
                "observed_at", 1753002785000L)))
                .isFalse();
    }
}
