package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.followup.DiagnosisFollowUpResult;
import vip.mate.troubleshooting.followup.DiagnosisFollowUpRun;
import vip.mate.troubleshooting.followup.DiagnosisFollowUpService;
import vip.mate.troubleshooting.service.TroubleshootingChatTranscriptService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

/** Diagnosis-bound deterministic questions and immutable supplemental runs. */
@RestController
@RequestMapping("/api/v1/troubleshooting/diagnoses/{diagnosisId}")
@RequiredArgsConstructor
public class DiagnosisFollowUpController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;
    private final DiagnosisFollowUpService service;
    private final TroubleshootingChatTranscriptService transcripts;

    @PostMapping("/follow-ups")
    @RequireWorkspaceRole("member")
    public R<DiagnosisFollowUpResult> followUp(
            @PathVariable String diagnosisId,
            @Valid @RequestBody FollowUpRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long resolvedWorkspace = workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId;
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
            DiagnosisFollowUpResult result = service.respond(
                    resolvedWorkspace,
                    diagnosisId,
                    request.clientTurnId(),
                    request.text(),
                    actor);
            if (transcriptAgentId != null) {
                transcripts.persistFollowUp(
                        resolvedWorkspace,
                        request.clientTurnId(),
                        request.chatConversationId(),
                        transcriptAgentId,
                        actor,
                        request.text(),
                        result);
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

    @GetMapping("/follow-up-runs")
    @RequireWorkspaceRole("viewer")
    public R<List<DiagnosisFollowUpRun>> runs(
            @PathVariable String diagnosisId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(service.runs(
                workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId,
                diagnosisId));
    }

    private String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new MateClawException(
                    "err.troubleshooting.actor_required", 401,
                    "diagnosis follow-up requires an authenticated operator");
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

    public record FollowUpRequest(
            @NotBlank @Size(max = 4000) String text,
            @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String clientTurnId,
            @Size(max = 128) String chatConversationId,
            @Pattern(regexp = "[1-9][0-9]{0,18}") String agentId) {

        /** Source compatibility for non-Chat clients; their transcript stays in the caller. */
        public FollowUpRequest(String text) {
            this(text, "legacy-" + java.util.UUID.randomUUID(), null, null);
        }
    }
}
