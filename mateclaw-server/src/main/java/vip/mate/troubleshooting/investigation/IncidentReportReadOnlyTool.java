package vip.mate.troubleshooting.investigation;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentContext;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Re-reads facts already normalized at incident intake without treating them as
 * source-observed telemetry.
 *
 * <p>This tool exists for alerts that already state the immediate failure point.
 * It deliberately emits the evidence grade {@code REPORTED}: the fact can seed a
 * low-confidence hypothesis, but it cannot prove why the upstream returned 502.
 * The raw URL, request body, stack and original alert text never enter evidence.
 */
@Component
public final class IncidentReportReadOnlyTool implements ReadOnlyEvidenceTool {

    public static final String TOOL_KEY = "incident-report";
    public static final String VERSION = "1";
    public static final String SIGNAL_KIND = "incident_reported_external_http_failure";
    private final Descriptor descriptor = new Descriptor(
            TOOL_KEY, VERSION, Capability.READ_EVIDENCE, Set.of(SIGNAL_KIND));

    @Override
    public Descriptor descriptor() {
        return descriptor;
    }

    @Override
    public EvidenceResult collect(
            ReadOnlyToolRegistry.Context context,
            EvidenceRequest request) {
        IncidentContext incident = context.incident();
        if (!SIGNAL_KIND.equals(request.signalKind())
                || !ReviewedIncidentPolicy.isIcareProductMapping502(incident)) {
            return new EvidenceResult(
                    request.requestId(), SIGNAL_KIND, "", EvidenceStatus.MISSING,
                    "规范化告警没有提供该失败点",
                    Map.of(), "incident-report:unavailable", evidenceTime(incident));
        }
        return new EvidenceResult(
                request.requestId(), SIGNAL_KIND, "", EvidenceStatus.ANOMALY,
                "告警明确记录：iCare 产品映射接口调用返回 HTTP 502；上游原因仍待取证",
                Map.of(
                        "failure_count", 1,
                        "http_status", "502",
                        "operation", "get_icare_product_mapping",
                        "evidence_grade", "REPORTED"),
                "incident-report:normalized",
                evidenceTime(incident));
    }

    private Instant evidenceTime(IncidentContext incident) {
        return incident == null || incident.occurredAt() == null
                ? Instant.EPOCH
                : incident.occurredAt();
    }
}
