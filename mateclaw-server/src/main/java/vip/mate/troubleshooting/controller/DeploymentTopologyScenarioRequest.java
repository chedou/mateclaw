package vip.mate.troubleshooting.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.IncidentImpact;

import java.time.Instant;
import java.util.UUID;

/** Small browser-owned business context; scenario, Playbook and Tool selection stay server-owned. */
public record DeploymentTopologyScenarioRequest(
        @NotBlank @Size(max = 128) String system,
        @NotBlank @Size(max = 128) String service,
        @NotBlank @Size(max = 500) String title,
        @Pattern(regexp = "P[0-3]", message = "severity must be P0, P1, P2 or P3")
        String severity,
        @Pattern(
                regexp = "^$|[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$",
                message = "traceId must be a safe identifier")
        String traceId,
        Boolean rehearsal) {

    public IncidentContext toIncidentContext(Instant reportedAt) {
        return new IncidentContext(
                "inc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                system,
                service,
                null,
                title,
                severity,
                IncidentImpact.unknown("部署拓扑网络路径影响待拨测确认"),
                traceId,
                reportedAt,
                null,
                "web:deployment-topology-scenario",
                IncidentCompleteness.STRUCTURED,
                null);
    }

    public boolean isRehearsal() {
        return rehearsal == null || rehearsal;
    }
}
