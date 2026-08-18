package vip.mate.troubleshooting.intake;

import java.time.Instant;
import java.util.List;

/**
 * Immutable intake aggregate, deliberately separate from Diagnosis.
 *
 * <p>An incomplete chat report may live here as AWAITING_INPUT without
 * polluting the diagnosis queue. Only READY sessions are eligible for the
 * separately caged read-only investigation path.</p>
 */
public record IntakeSession(
        String intakeSessionId,
        String contractVersion,
        long workspaceId,
        String source,
        String conversationRef,
        String reporterRef,
        IntakeSessionStatus status,
        String symptom,
        String system,
        String service,
        String customerRef,
        String errorCode,
        String traceId,
        Instant occurredAt,
        List<IntakeAttachmentRef> attachments,
        List<String> missingFields,
        Instant reportedAt,
        Instant readyAt,
        Instant lastMessageAt,
        List<IntakeSessionEvent> timeline,
        NormalizedIncidentFactKind normalizedFactKind) {

    public static final String CURRENT_CONTRACT_VERSION = "intake-session.v1";

    public IntakeSession {
        if (intakeSessionId == null || intakeSessionId.isBlank()) {
            throw new IllegalArgumentException("intakeSessionId must not be blank");
        }
        if (!CURRENT_CONTRACT_VERSION.equals(contractVersion)) {
            throw new IllegalArgumentException("unsupported intake contractVersion: " + contractVersion);
        }
        if (workspaceId <= 0 || source == null || source.isBlank()
                || conversationRef == null || conversationRef.isBlank()
                || reporterRef == null || reporterRef.isBlank()) {
            throw new IllegalArgumentException("intake session routing identity is incomplete");
        }
        if (status == null || reportedAt == null || lastMessageAt == null) {
            throw new IllegalArgumentException("intake session state/timestamps are incomplete");
        }
        if (readyAt != null && readyAt.isBefore(reportedAt)) {
            throw new IllegalArgumentException("readyAt cannot precede reportedAt");
        }
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
        if (status == IntakeSessionStatus.READY && !missingFields.isEmpty()) {
            throw new IllegalArgumentException("READY intake session cannot have missing fields");
        }
        if (status == IntakeSessionStatus.READY && readyAt == null) {
            throw new IllegalArgumentException("READY intake session requires readyAt");
        }
        if (status == IntakeSessionStatus.AWAITING_INPUT && missingFields.isEmpty()) {
            throw new IllegalArgumentException("AWAITING_INPUT requires at least one missing field");
        }
        if (status == IntakeSessionStatus.AWAITING_INPUT && readyAt != null) {
            throw new IllegalArgumentException("AWAITING_INPUT cannot have readyAt");
        }
    }

    /** Source compatibility for sessions predating structured normalized provenance. */
    public IntakeSession(
            String intakeSessionId,
            String contractVersion,
            long workspaceId,
            String source,
            String conversationRef,
            String reporterRef,
            IntakeSessionStatus status,
            String symptom,
            String system,
            String service,
            String customerRef,
            String errorCode,
            String traceId,
            Instant occurredAt,
            List<IntakeAttachmentRef> attachments,
            List<String> missingFields,
            Instant reportedAt,
            Instant readyAt,
            Instant lastMessageAt,
            List<IntakeSessionEvent> timeline) {
        this(
                intakeSessionId, contractVersion, workspaceId, source, conversationRef,
                reporterRef, status, symptom, system, service, customerRef, errorCode,
                traceId, occurredAt, attachments, missingFields, reportedAt, readyAt,
                lastMessageAt, timeline, null);
    }
}
