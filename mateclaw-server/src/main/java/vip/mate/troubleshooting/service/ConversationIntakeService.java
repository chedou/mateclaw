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
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjectionService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
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
            Boolean rehearsal) {
        return turn(workspaceId, reporterRef, conversationId, null, text, rehearsal);
    }

    public ConversationTurnResult turn(
            long workspaceId,
            String reporterRef,
            String conversationId,
            String clientTurnId,
            String text,
            Boolean rehearsal) {
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
        String normalizedClientTurnId = clientTurnId == null || clientTurnId.isBlank()
                ? null
                : clientTurnId.trim();
        String messageId = normalizedClientTurnId == null
                ? "web-msg-" + UUID.randomUUID()
                : scopedMessageId(
                        reporterRef.trim(), conversationRef, normalizedClientTurnId);
        String deliveryConversationId = TroubleshootingIntakeSources.WEB_CONVERSATION
                + ":" + conversationRef;
        IntakeMessageEnvelope envelope = new IntakeMessageEnvelope(
                workspaceId,
                TroubleshootingIntakeSources.WEB_CONVERSATION,
                messageId,
                conversationRef,
                deliveryConversationId,
                reporterRef.trim(),
                text.trim(),
                List.of(),
                receivedAt);
        IntakeDecision decision = normalizedClientTurnId == null
                ? sessions.acceptConversation(envelope, rehearsal)
                : sessions.acceptConversation(
                        envelope,
                        rehearsal,
                        legacyReceiptLookupAlias(normalizedClientTurnId));
        String diagnosisId = null;
        Boolean created = null;
        String prompt = decision.prompt();
        var aggregate = sessions.get(workspaceId, decision.intakeSessionId());
        if (aggregate == null && decision.status() == IntakeSessionStatus.READY) {
            aggregate = sessions.getReady(workspaceId, decision.intakeSessionId());
        }
        boolean lockedRehearsal = requireLockedMode(aggregate);
        String transcriptUserMessage = aggregate == null
                ? "排障告警（已规范化）\n原文未保存"
                : renderTranscriptUserMessage(aggregate);
        if (!decision.missingFields().isEmpty()) {
            transcriptUserMessage += "\n当前还需补充："
                    + String.join("、", decision.missingFields());
        }
        if (decision.status() == IntakeSessionStatus.READY) {
            var ready = aggregate;
            transcriptUserMessage = renderTranscriptUserMessage(ready);
            lockedRehearsal = requireLockedMode(ready);
            StoredDiagnosis stored = intakeService.report(ready, lockedRehearsal);
            diagnosisId = stored.diagnosis().diagnosisId();
            created = stored.created();
            prompt = renderInvestigationRoute(stored.diagnosis())
                    + "\n\n"
                    + summaryRenderer.render(
                            projectionService.project(workspaceId, diagnosisId)
                                    .businessSummary());
            if (Boolean.FALSE.equals(created)) {
                prompt = "已汇合到既有排障单。\n" + prompt;
            }
            prompt = prompt + "\n\n"
                    + (lockedRehearsal ? "已生成演练排障单" : "已生成正式排障单")
                    + "：" + diagnosisId
                    + "\n[打开排障详情](/troubleshooting?view=detail&diagnosisId="
                    + diagnosisId + ")"
                    + "\n\n可以继续问“为什么是这个原因”“有哪些证据”"
                    + "“还缺什么”“下一步查什么”；输入“结束排障”才会退出。";
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
                lockedRehearsal,
                transcriptUserMessage);
    }

    /**
     * Makes the already-enforced SOP-first routing visible in Chat.
     *
     * <p>The Intake service chooses and freezes the route before collecting
     * evidence. This renderer only explains that persisted result; it never
     * re-matches mutable SOP data after the investigation has completed.</p>
     */
    private String renderInvestigationRoute(Diagnosis diagnosis) {
        if (diagnosis == null || diagnosis.investigationMode() == null) {
            throw new IllegalStateException(
                    "persisted Diagnosis has no investigation route");
        }
        if (diagnosis.investigationMode() == InvestigationMode.OPEN_DISCOVERY) {
            return "排障路径：未找到可正式执行的已审核 SOP，"
                    + "已进入通用只读调查。";
        }
        if (diagnosis.sourcePlaybookVersionRef() == null) {
            return "排障路径：历史排障记录未冻结准确 SOP 版本，"
                    + "无法确认当时采用哪一版 SOP；未使用当前 SOP 反推。";
        }
        String title = firstNonBlank(
                diagnosis.sopTitle(),
                diagnosis.sopKey(),
                diagnosis.sourcePlaybookVersionRef().playbookId());
        String safeTitle = vip.mate.troubleshooting.TroubleshootingBusinessTextPolicy
                .forChannel(title, 160);
        String safeVersion = vip.mate.troubleshooting.TroubleshootingBusinessTextPolicy
                .forChannel(
                        diagnosis.sourcePlaybookVersionRef().playbookId()
                                + "@v"
                                + diagnosis.sourcePlaybookVersionRef().playbookVersion(),
                        160);
        return "排障路径：已匹配并采用已审核 SOP「" + safeTitle + "」"
                + "（" + safeVersion + "），按该 SOP 进行只读取证和判断。";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        throw new IllegalStateException(
                "Playbook Diagnosis has no displayable SOP identity");
    }

    /**
     * Browser turn ids are idempotency tokens, not globally trusted receipt
     * ids. Scope and hash them so reusing a known token cannot address another
     * operator's IntakeSession and no actor/conversation identifier is stored
     * in plaintext in the receipt timeline.
     */
    private String scopedMessageId(
            String reporterRef,
            String conversationRef,
            String clientTurnId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest((reporterRef + "\u0000"
                    + conversationRef + "\u0000" + clientTurnId)
                    .getBytes(StandardCharsets.UTF_8));
            return "web-msg-" + HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Reconstructs the pre-upgrade receipt id only as a bounded database read
     * alias. New receipts always use {@link #scopedMessageId(String, String, String)};
     * this value is never persisted or exposed in a response.
     */
    private String legacyReceiptLookupAlias(String clientTurnId) {
        return "web-msg-" + clientTurnId;
    }

    /** Restores the immutable server-owned mode for a reopened conversation. */
    public ConversationModeResult mode(
            long workspaceId,
            String reporterRef,
            String conversationId) {
        requireReporter(reporterRef);
        var session = sessions.getConversationMode(
                workspaceId, conversationId, reporterRef.trim());
        return new ConversationModeResult(
                session.conversationRef(),
                session.intakeSessionId(),
                session.status().name(),
                requireLockedMode(session));
    }

    private boolean requireLockedMode(
            vip.mate.troubleshooting.intake.IntakeSession session) {
        if (session == null || session.rehearsal() == null) {
            throw new MateClawException(
                    "err.troubleshooting.conversation_mode_unavailable",
                    409,
                    "troubleshooting conversation has no locked mode");
        }
        return session.rehearsal();
    }

    private void requireReporter(String reporterRef) {
        if (reporterRef == null || reporterRef.isBlank()) {
            throw new MateClawException(
                    "err.troubleshooting.actor_required",
                    401,
                    "conversation intake requires an authenticated operator");
        }
    }

    private String renderTranscriptUserMessage(vip.mate.troubleshooting.intake.IntakeSession session) {
        List<String> lines = new ArrayList<>();
        lines.add("排障告警（已规范化）");
        lines.add("原文未保存");
        if (session.normalizedFactKind() != null) {
            addLine(lines, "系统", session.system());
            addLine(lines, "服务", session.service());
            addLine(lines, "错误码", session.errorCode());
        } else {
            addTechnicalLine(lines, "系统", session.system());
            addTechnicalLine(lines, "服务", session.service());
            addTechnicalLine(lines, "错误码", session.errorCode());
        }
        if (session.occurredAt() != null) {
            addLine(lines, "发生时间", session.occurredAt().toString());
        }
        if (session.normalizedFactKind() != null) {
            addLine(lines, "现象", session.symptom());
        } else if (session.symptom() != null && !session.symptom().isBlank()) {
            lines.add("现象：已识别（可能含个人或工单信息，原文未保存）");
        }
        return String.join("\n", lines);
    }

    private void addLine(List<String> lines, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String safe = vip.mate.troubleshooting.TroubleshootingBusinessTextPolicy.forChannel(
                value, 500);
        lines.add(label + "：" + safe);
    }

    private void addTechnicalLine(List<String> lines, String label, String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.matches("[A-Za-z0-9._:-]{1,128}")) {
            addLine(lines, label, normalized);
        }
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
            boolean rehearsal,
            @com.fasterxml.jackson.annotation.JsonIgnore String transcriptUserMessage) {
    }

    public record ConversationModeResult(
            String conversationId,
            String intakeSessionId,
            String status,
            boolean rehearsal) {
    }
}
