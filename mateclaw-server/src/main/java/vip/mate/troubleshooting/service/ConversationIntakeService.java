package vip.mate.troubleshooting.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.intake.IntakeDecision;
import vip.mate.troubleshooting.intake.IntakeMessageEnvelope;
import vip.mate.troubleshooting.intake.IntakeSessionStatus;
import vip.mate.troubleshooting.intake.TroubleshootingChannelSummaryRenderer;
import vip.mate.troubleshooting.intake.TroubleshootingIntakeSessionService;
import vip.mate.troubleshooting.intake.TroubleshootingIntakeSources;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjectionService;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Web conversation entry into the same IntakeSession machinery as WeCom.
 *
 * <p>Incomplete turns stay in {@code AWAITING_INPUT} with a deterministic prompt.
 * When fields are complete, this seam reports the Diagnosis synchronously and
 * returns the same channel business summary used by WeCom — so the operator can
 * stay in the current chat without being forced onto the workbench page.</p>
 */
@Service
public class ConversationIntakeService {

    private final TroubleshootingIntakeSessionService sessions;
    private final TroubleshootingIntakeService intakeService;
    private final DiagnosisExperienceProjectionService projectionService;
    private final TroubleshootingChannelSummaryRenderer summaryRenderer;
    private final Clock clock;

    @Autowired
    public ConversationIntakeService(
            TroubleshootingIntakeSessionService sessions,
            TroubleshootingIntakeService intakeService,
            DiagnosisExperienceProjectionService projectionService,
            TroubleshootingChannelSummaryRenderer summaryRenderer) {
        this(sessions, intakeService, projectionService, summaryRenderer, Clock.systemUTC());
    }

    ConversationIntakeService(
            TroubleshootingIntakeSessionService sessions,
            TroubleshootingIntakeService intakeService,
            DiagnosisExperienceProjectionService projectionService,
            TroubleshootingChannelSummaryRenderer summaryRenderer,
            Clock clock) {
        this.sessions = sessions;
        this.intakeService = intakeService;
        this.projectionService = projectionService;
        this.summaryRenderer = summaryRenderer;
        this.clock = clock;
    }

    public ConversationTurnResult turn(
            long workspaceId,
            String reporterRef,
            String conversationId,
            String text,
            boolean rehearsal) {
        if (reporterRef == null || reporterRef.isBlank()) {
            throw new MateClawException(
                    "err.troubleshooting.actor_required",
                    401,
                    "conversation intake requires an authenticated operator");
        }
        if (text == null || text.isBlank()) {
            throw new MateClawException(
                    "err.troubleshooting.conversation_text_required",
                    400,
                    "conversation turn text must not be blank");
        }
        String conversationRef = conversationId == null || conversationId.isBlank()
                ? "web-conv-" + UUID.randomUUID()
                : conversationId.trim();
        Instant receivedAt = clock.instant();
        String messageId = "web-msg-" + UUID.randomUUID();
        String deliveryConversationId = TroubleshootingIntakeSources.WEB_CONVERSATION
                + ":" + conversationRef;
        IntakeDecision decision = sessions.accept(new IntakeMessageEnvelope(
                workspaceId,
                TroubleshootingIntakeSources.WEB_CONVERSATION,
                messageId,
                conversationRef,
                deliveryConversationId,
                reporterRef.trim(),
                text.trim(),
                List.of(),
                receivedAt));
        String diagnosisId = null;
        Boolean created = null;
        String prompt = decision.prompt();
        if (decision.status() == IntakeSessionStatus.READY) {
            StoredDiagnosis stored = intakeService.report(
                    sessions.getReady(workspaceId, decision.intakeSessionId()),
                    rehearsal);
            diagnosisId = stored.diagnosis().diagnosisId();
            created = stored.created();
            prompt = summaryRenderer.render(
                    projectionService.project(workspaceId, diagnosisId).businessSummary());
            if (Boolean.FALSE.equals(created)) {
                prompt = "已汇合到既有排障单。\n" + prompt;
            }
        }
        return new ConversationTurnResult(
                conversationRef,
                decision.intakeSessionId(),
                decision.status().name(),
                decision.missingFields(),
                prompt,
                decision.duplicate(),
                decision.outOfOrder(),
                diagnosisId,
                created,
                rehearsal);
    }

    public record ConversationTurnResult(
            String conversationId,
            String intakeSessionId,
            String status,
            List<String> missingFields,
            String prompt,
            boolean duplicate,
            boolean outOfOrder,
            String diagnosisId,
            Boolean created,
            boolean rehearsal) {
    }
}
