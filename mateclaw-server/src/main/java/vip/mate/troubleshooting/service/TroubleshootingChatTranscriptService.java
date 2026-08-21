package vip.mate.troubleshooting.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingBusinessTextPolicy;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.followup.DiagnosisFollowUpIntent;
import vip.mate.troubleshooting.followup.DiagnosisFollowUpResult;
import vip.mate.troubleshooting.model.TroubleshootingChatTurnEntity;
import vip.mate.troubleshooting.repository.TroubleshootingChatTurnMapper;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageContentPart;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

/** Persists the safe, user-visible troubleshooting transcript in the normal chat tables. */
@Service
@RequiredArgsConstructor
public class TroubleshootingChatTranscriptService {

    private static final int MAX_TRANSCRIPT_TEXT = 4000;
    private static final String PENDING_USER = "排障请求已提交（原文未保存）";
    private static final String PENDING_ASSISTANT =
            "正在处理这轮排障。若刷新后仍显示本状态，请重新发送上一条问题，系统会复用本轮记录。";
    private static final String FAILED_ASSISTANT =
            "这轮排障未完成。请重新发送上一条问题，系统会复用本轮记录，不会重复追加调查。";

    private final ConversationService conversations;
    private final ObjectMapper objectMapper;
    private final TroubleshootingChatTurnMapper turns;

    /**
     * Creates a durable, PII-free pending pair before domain work starts.
     * A response loss therefore leaves a recoverable server-side turn instead
     * of a Diagnosis or immutable run with no visible chat history.
     */
    @Transactional
    public void begin(PendingTurn turn) {
        var conversation = conversations.getOrCreateConversation(
                turn.chatConversationId(), turn.agentId(), turn.actorRef(), turn.workspaceId());
        if (conversation != null && conversation.getAgentId() != null
                && !conversation.getAgentId().equals(turn.agentId())) {
            throw new MateClawException(
                    "err.troubleshooting.chat_agent_mismatch", 409,
                    "chat transcript agent does not match the existing conversation");
        }
        conversations.lockConversation(turn.chatConversationId());
        TroubleshootingChatTurnEntity existing = turns.findForUpdate(
                turn.workspaceId(), turn.chatConversationId(), turn.clientTurnId());
        if (existing != null) {
            requireSameTarget(existing, turn);
            return;
        }
        MessageEntity user = conversations.saveMessage(
                turn.chatConversationId(),
                "user",
                PENDING_USER,
                List.of(MessageContentPart.text(PENDING_USER)),
                "completed", 0, 0, null, null, pendingMetadata(turn, "user"));
        MessageEntity assistant = conversations.saveMessage(
                turn.chatConversationId(),
                "assistant",
                PENDING_ASSISTANT,
                List.of(MessageContentPart.text(PENDING_ASSISTANT)),
                "generating", 0, 0, null, null, pendingMetadata(turn, "assistant"));
        TroubleshootingChatTurnEntity row = new TroubleshootingChatTurnEntity();
        row.setWorkspaceId(turn.workspaceId());
        row.setConversationId(turn.chatConversationId());
        row.setClientTurnId(turn.clientTurnId());
        row.setAgentId(turn.agentId());
        row.setUserMessageId(user.getId());
        row.setAssistantMessageId(assistant.getId());
        row.setDeleted(0);
        LocalDateTime now = LocalDateTime.now();
        row.setCreateTime(now);
        row.setUpdateTime(now);
        if (turns.insert(row) != 1) {
            throw new MateClawException(
                    "err.troubleshooting.chat_turn_not_persisted", 500,
                    "troubleshooting chat turn could not be persisted");
        }
    }

    /** The final pair replacement is atomic and idempotent under the conversation row lock. */
    @Transactional
    public void persist(TranscriptTurn turn) {
        begin(new PendingTurn(
                turn.workspaceId(), turn.clientTurnId(), turn.chatConversationId(),
                turn.agentId(), turn.actorRef()));
        conversations.lockConversation(turn.chatConversationId());
        TroubleshootingChatTurnEntity existing = turns.findForUpdate(
                turn.workspaceId(), turn.chatConversationId(), turn.clientTurnId());
        if (existing == null) {
            throw new MateClawException(
                    "err.troubleshooting.chat_turn_not_persisted", 500,
                    "troubleshooting chat turn pointer is missing");
        }
        requireSameTarget(existing, new PendingTurn(
                turn.workspaceId(), turn.clientTurnId(), turn.chatConversationId(),
                turn.agentId(), turn.actorRef()));
        MessageEntity user = conversations.getMessage(existing.getUserMessageId());
        MessageEntity assistant = conversations.getMessage(existing.getAssistantMessageId());
        if (isCompleted(user, assistant)) {
            requireSameTurn(existing, turn, user, assistant);
            return;
        }
        requirePendingPair(turn.chatConversationId(), user, assistant);
        conversations.replaceTroubleshootingMessage(
                existing.getUserMessageId(), turn.chatConversationId(), "user",
                List.of("completed"), turn.userContent(),
                List.of(MessageContentPart.text(turn.userContent())),
                "completed", metadata(turn, "user"));
        conversations.replaceTroubleshootingMessage(
                existing.getAssistantMessageId(), turn.chatConversationId(), "assistant",
                List.of("generating", "failed"), turn.assistantContent(),
                List.of(MessageContentPart.text(turn.assistantContent())),
                "completed", metadata(turn, "assistant"));
    }

    /** Marks an ordinary request failure without discarding the durable retry identity. */
    @Transactional
    public void fail(PendingTurn turn) {
        conversations.lockConversation(turn.chatConversationId());
        TroubleshootingChatTurnEntity existing = turns.findForUpdate(
                turn.workspaceId(), turn.chatConversationId(), turn.clientTurnId());
        if (existing == null) {
            return;
        }
        requireSameTarget(existing, turn);
        MessageEntity user = conversations.getMessage(existing.getUserMessageId());
        MessageEntity assistant = conversations.getMessage(existing.getAssistantMessageId());
        if (isCompleted(user, assistant)) {
            return;
        }
        requirePendingPair(turn.chatConversationId(), user, assistant);
        conversations.replaceTroubleshootingMessage(
                existing.getUserMessageId(), turn.chatConversationId(), "user",
                List.of("completed"), PENDING_USER,
                List.of(MessageContentPart.text(PENDING_USER)),
                "completed", retryMetadata(turn, "user"));
        conversations.replaceTroubleshootingMessage(
                existing.getAssistantMessageId(), turn.chatConversationId(), "assistant",
                List.of("generating", "failed"), FAILED_ASSISTANT,
                List.of(MessageContentPart.text(FAILED_ASSISTANT)),
                "failed", retryMetadata(turn, "assistant"));
    }

    @Transactional
    public void persistFollowUp(
            long workspaceId,
            String clientTurnId,
            String chatConversationId,
            long agentId,
            String actorRef,
            String question,
            DiagnosisFollowUpResult result) {
        persist(new TranscriptTurn(
                workspaceId,
                clientTurnId,
                chatConversationId,
                agentId,
                actorRef,
                safeFollowUpQuestion(result.intent(), question),
                result.answer(),
                null,
                null,
                result.diagnosisId(),
                result.intent(),
                result.investigationRun() == null ? null : result.investigationRun().runId()));
    }

    private String pendingMetadata(PendingTurn turn, String transcriptRole) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("type", "troubleshooting_transcript");
        metadata.put("troubleshooting", true);
        metadata.put("transcriptStatus", "PENDING");
        metadata.put("transcriptRole", transcriptRole);
        metadata.put("agentId", Long.toString(turn.agentId()));
        metadata.put("clientTurnId", turn.clientTurnId());
        return serializeMetadata(metadata);
    }

    private String retryMetadata(PendingTurn turn, String transcriptRole) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("type", "troubleshooting_transcript");
        metadata.put("troubleshooting", true);
        metadata.put("transcriptStatus", "FAILED_RETRYABLE");
        metadata.put("transcriptRole", transcriptRole);
        metadata.put("agentId", Long.toString(turn.agentId()));
        metadata.put("clientTurnId", turn.clientTurnId());
        return serializeMetadata(metadata);
    }

    /**
     * Fixed questions are useful conversation history. Supplemental or unclassified payloads
     * may contain raw logs/PII, so only their safe intent receipt is retained.
     */
    public String safeFollowUpQuestion(DiagnosisFollowUpIntent intent, String question) {
        return switch (intent) {
            case WHY -> "追问：为什么是这个原因";
            case EVIDENCE -> "追问：有哪些证据";
            case UNKNOWNS -> "追问：还缺什么";
            case NEXT_STEP -> "追问：下一步查什么";
            case END -> "结束排障";
            case SUPPLEMENTAL_EVIDENCE -> "补充证据：已提交一条待验证事实摘要（原文未保存）";
            case HELP -> "继续排障（未匹配固定追问，原文未保存）";
        };
    }

    private String metadata(TranscriptTurn turn, String transcriptRole) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("type", "troubleshooting_transcript");
        metadata.put("troubleshooting", true);
        metadata.put("transcriptRole", transcriptRole);
        metadata.put("agentId", Long.toString(turn.agentId()));
        metadata.put("clientTurnId", turn.clientTurnId());
        put(metadata, "intakeSessionId", turn.intakeSessionId());
        put(metadata, "intakeConversationId", turn.intakeConversationId());
        put(metadata, "diagnosisId", turn.diagnosisId());
        put(metadata, "followUpIntent", turn.followUpIntent() == null
                ? null : turn.followUpIntent().name());
        put(metadata, "investigationRunId", turn.investigationRunId());
        metadata.put("transcriptStatus", "COMPLETED");
        return serializeMetadata(metadata);
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException failure) {
            throw new MateClawException(
                    "err.troubleshooting.transcript_metadata_invalid", 500,
                    "cannot serialize troubleshooting transcript metadata");
        }
    }

    private void put(Map<String, Object> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, value.trim());
        }
    }

    private void requireSameTarget(TroubleshootingChatTurnEntity existing, PendingTurn turn) {
        boolean same = existing.getWorkspaceId() != null
                && existing.getWorkspaceId() == turn.workspaceId()
                && turn.chatConversationId().equals(existing.getConversationId())
                && turn.clientTurnId().equals(existing.getClientTurnId())
                && existing.getAgentId() != null && existing.getAgentId() == turn.agentId();
        if (!same) {
            throw new MateClawException(
                    "err.troubleshooting.chat_turn_conflict", 409,
                    "clientTurnId already belongs to a different chat target");
        }
    }

    private boolean isCompleted(MessageEntity user, MessageEntity assistant) {
        return user != null && assistant != null
                && "completed".equals(user.getStatus())
                && "completed".equals(assistant.getStatus());
    }

    private void requirePendingPair(
            String conversationId, MessageEntity user, MessageEntity assistant) {
        boolean pending = user != null && assistant != null
                && conversationId.equals(user.getConversationId())
                && conversationId.equals(assistant.getConversationId())
                && "user".equals(user.getRole()) && "assistant".equals(assistant.getRole())
                && ("generating".equals(assistant.getStatus())
                || "failed".equals(assistant.getStatus()));
        if (!pending) {
            throw new MateClawException(
                    "err.troubleshooting.chat_turn_conflict", 409,
                    "stored troubleshooting chat turn is neither pending nor completed");
        }
    }

    private void requireSameTurn(
            TroubleshootingChatTurnEntity existing, TranscriptTurn turn,
            MessageEntity user, MessageEntity assistant) {
        boolean same = existing.getAgentId() != null && existing.getAgentId() == turn.agentId()
                && user != null && assistant != null
                && turn.chatConversationId().equals(user.getConversationId())
                && turn.chatConversationId().equals(assistant.getConversationId())
                && "user".equals(user.getRole()) && "assistant".equals(assistant.getRole())
                && turn.userContent().equals(user.getContent())
                && turn.assistantContent().equals(assistant.getContent())
                && metadata(turn, "user").equals(user.getMetadata())
                && metadata(turn, "assistant").equals(assistant.getMetadata());
        if (!same) {
            throw new MateClawException(
                    "err.troubleshooting.chat_turn_conflict", 409,
                    "clientTurnId already belongs to a different chat payload");
        }
    }

    public record PendingTurn(
            long workspaceId,
            String clientTurnId,
            String chatConversationId,
            long agentId,
            String actorRef) {

        public PendingTurn {
            if (workspaceId <= 0 || agentId <= 0
                    || clientTurnId == null || clientTurnId.isBlank()
                    || chatConversationId == null || chatConversationId.isBlank()
                    || actorRef == null || actorRef.isBlank()) {
                throw new IllegalArgumentException("pending troubleshooting chat turn is incomplete");
            }
            clientTurnId = clientTurnId.trim();
            chatConversationId = chatConversationId.trim();
            actorRef = actorRef.trim();
        }
    }

    public record TranscriptTurn(
            long workspaceId,
            String clientTurnId,
            String chatConversationId,
            long agentId,
            String actorRef,
            String userContent,
            String assistantContent,
            String intakeConversationId,
            String intakeSessionId,
            String diagnosisId,
            DiagnosisFollowUpIntent followUpIntent,
            String investigationRunId) {

        /** Source compatibility for focused callers created before turn idempotency. */
        public TranscriptTurn(
                long workspaceId,
                String chatConversationId,
                long agentId,
                String actorRef,
                String userContent,
                String assistantContent,
                String intakeSessionId,
                String diagnosisId,
                DiagnosisFollowUpIntent followUpIntent,
                String investigationRunId) {
            this(workspaceId, "legacy-" + java.util.UUID.randomUUID(), chatConversationId,
                    agentId, actorRef, userContent, assistantContent, null, intakeSessionId,
                    diagnosisId, followUpIntent, investigationRunId);
        }

        public TranscriptTurn {
            if (workspaceId <= 0 || agentId <= 0
                    || clientTurnId == null || clientTurnId.isBlank()
                    || chatConversationId == null || chatConversationId.isBlank()
                    || actorRef == null || actorRef.isBlank()
                    || userContent == null || userContent.isBlank()
                    || assistantContent == null || assistantContent.isBlank()) {
                throw new IllegalArgumentException("troubleshooting transcript turn is incomplete");
            }
            clientTurnId = clientTurnId.trim();
            chatConversationId = chatConversationId.trim();
            actorRef = actorRef.trim();
            userContent = TroubleshootingBusinessTextPolicy.truncate(
                    TroubleshootingSecretRedactor.redact(userContent.trim()), MAX_TRANSCRIPT_TEXT);
            assistantContent = TroubleshootingBusinessTextPolicy.truncate(
                    TroubleshootingSecretRedactor.redact(assistantContent.trim()), MAX_TRANSCRIPT_TEXT);
            assistantContent = assistantContent.replaceFirst(
                    "^已汇合到既有排障单。\\s*", "");
        }
    }
}
