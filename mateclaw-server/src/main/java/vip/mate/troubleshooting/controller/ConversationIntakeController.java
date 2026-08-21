package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.service.ConversationIntakeService;
import vip.mate.troubleshooting.service.TroubleshootingChatTranscriptService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/**
 * Authenticated Web conversation entry that reuses IntakeSession.
 *
 * <p>This is the workbench alternate to the form-based incident report. It does
 * not invent a second Diagnosis path: incomplete turns stay in intake, READY
 * turns call the same report seam used by WeCom after async investigation enqueue.</p>
 */
@RestController
@RequestMapping("/api/v1/troubleshooting/conversation")
@RequiredArgsConstructor
public class ConversationIntakeController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final ConversationIntakeService conversationIntakeService;
    private final TroubleshootingChatTranscriptService transcripts;

    @PostMapping("/turns")
    @RequireWorkspaceRole("member")
    public R<ConversationIntakeService.ConversationTurnResult> turn(
            @Valid @RequestBody ConversationTurnRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long resolvedWorkspace = resolveWorkspace(workspaceId);
        String actor = currentActor();
        Long transcriptAgentId = resolveTranscriptAgentId(
                request.chatConversationId(), request.agentId());
        requireClientTurnId(transcriptAgentId, request.clientTurnId());
        TroubleshootingChatTranscriptService.PendingTurn pending = transcriptAgentId == null
                ? null
                : new TroubleshootingChatTranscriptService.PendingTurn(
                    resolvedWorkspace,
                    request.clientTurnId(),
                    request.chatConversationId(),
                    transcriptAgentId,
                    actor);
        if (pending != null) {
            transcripts.begin(pending);
        }
        try {
            var result = conversationIntakeService.turn(
                    resolvedWorkspace,
                    actor,
                    request.conversationId(),
                    request.clientTurnId(),
                    request.text(),
                    request.isRehearsal());
            if (transcriptAgentId != null) {
                transcripts.persist(new TroubleshootingChatTranscriptService.TranscriptTurn(
                        resolvedWorkspace,
                        request.clientTurnId(),
                        request.chatConversationId(),
                        transcriptAgentId,
                        actor,
                        result.transcriptUserMessage(),
                        result.prompt(),
                        result.conversationId(),
                        result.intakeSessionId(),
                        result.diagnosisId(),
                        null,
                        null));
            }
            return R.ok(result);
        } catch (RuntimeException failure) {
            if (pending != null) {
                try {
                    transcripts.fail(pending);
                } catch (RuntimeException transcriptFailure) {
                    failure.addSuppressed(transcriptFailure);
                }
            }
            throw failure;
        }
    }

    private long resolveWorkspace(Long workspaceId) {
        return workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId;
    }

    private String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new MateClawException(
                    "err.troubleshooting.actor_required",
                    401,
                    "conversation intake requires an authenticated operator");
        }
        return auth.getName();
    }

    private Long resolveTranscriptAgentId(String conversationId, String agentId) {
        boolean hasConversation = conversationId != null && !conversationId.isBlank();
        boolean hasAgent = agentId != null && !agentId.isBlank();
        if (hasConversation != hasAgent) {
            throw new MateClawException(
                    "err.troubleshooting.chat_transcript_target_incomplete", 400,
                    "chatConversationId and agentId must be supplied together");
        }
        if (!hasConversation) {
            return null;
        }
        try {
            long parsed = Long.parseLong(agentId);
            if (parsed <= 0) throw new NumberFormatException("not positive");
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new MateClawException(
                    "err.troubleshooting.chat_agent_invalid", 400,
                    "chat agentId must be a positive decimal identifier");
        }
    }

    private void requireClientTurnId(Long transcriptAgentId, String clientTurnId) {
        if (transcriptAgentId != null && (clientTurnId == null || clientTurnId.isBlank())) {
            throw new MateClawException(
                    "err.troubleshooting.client_turn_required", 400,
                    "clientTurnId is required when persisting a chat transcript");
        }
    }

    public record ConversationTurnRequest(
            @Size(max = 128) String conversationId,
            @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String clientTurnId,
            @Size(max = 128) String chatConversationId,
            @Pattern(regexp = "[1-9][0-9]{0,18}") String agentId,
            @NotBlank @Size(max = 4000) String text,
            Boolean rehearsal) {

        /** Source compatibility for callers that do not render inside the Chat console. */
        public ConversationTurnRequest(String conversationId, String text, Boolean rehearsal) {
            this(conversationId, "legacy-" + java.util.UUID.randomUUID(), null, null, text, rehearsal);
        }

        /** First-time Web use is rehearsal unless the operator explicitly opts into formal intake. */
        public boolean isRehearsal() {
            return rehearsal == null || rehearsal;
        }
    }
}
