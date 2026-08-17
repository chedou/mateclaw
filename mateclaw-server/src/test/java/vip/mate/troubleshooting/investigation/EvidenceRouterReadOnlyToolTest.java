package vip.mate.troubleshooting.investigation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceRouterReadOnlyToolTest {

    @Test
    void descriptorDoesNotClaimTheLocalIncidentReportedSignal() {
        EvidenceRouterReadOnlyTool tool = new EvidenceRouterReadOnlyTool(null);

        assertThat(tool.descriptor().signalKinds())
                .contains("error_log_scan")
                .doesNotContain("incident_reported_external_http_failure");
    }
}
