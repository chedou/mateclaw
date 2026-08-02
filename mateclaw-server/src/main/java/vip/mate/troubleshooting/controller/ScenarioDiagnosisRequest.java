package vip.mate.troubleshooting.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.IncidentImpact;

import java.time.Instant;
import java.util.UUID;

/**
 * Business context for an explicitly selected scenario.
 *
 * <p>There is deliberately no {@code errorCode} field. This is the entry for
 * faults that have no code — supplying one here would let a caller reach the
 * deterministic error-code authority through a door that does not check it.</p>
 *
 * <p>The evidence plan, the Playbook and the tool selection all stay
 * server-owned: the caller says what broke, not how to look at it.</p>
 */
public record ScenarioDiagnosisRequest(
        @NotBlank @Size(max = 128) String system,
        @NotBlank @Size(max = 128) String service,
        @NotBlank @Size(max = 500) String title,
        @Pattern(regexp = "P[0-3]", message = "severity must be P0, P1, P2 or P3")
        String severity,
        @Pattern(
                regexp = "^$|[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$",
                message = "traceId must be a safe identifier")
        String traceId,
        @Size(max = 500) String customerRef,
        Boolean rehearsal) {

    public IncidentContext toIncidentContext(Instant reportedAt) {
        return new IncidentContext(
                "inc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                system,
                service,
                null,
                title,
                severity,
                IncidentImpact.unknown(customerRef == null || customerRef.isBlank()
                        ? "影响面待只读取证确认"
                        : "客户/影响对象: " + customerRef),
                traceId,
                reportedAt,
                null,
                "web:scenario",
                IncidentCompleteness.SYMPTOM,
                title);
    }

    public boolean isRehearsal() {
        return Boolean.TRUE.equals(rehearsal);
    }
}
