package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

    @PostMapping("/turns")
    @RequireWorkspaceRole("member")
    public R<ConversationIntakeService.ConversationTurnResult> turn(
            @Valid @RequestBody ConversationTurnRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(conversationIntakeService.turn(
                resolveWorkspace(workspaceId),
                currentActor(),
                request.conversationId(),
                request.text(),
                request.isRehearsal()));
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

    public record ConversationTurnRequest(
            @Size(max = 128) String conversationId,
            @NotBlank @Size(max = 4000) String text,
            Boolean rehearsal) {

        /** First-time Web use is rehearsal unless the operator explicitly opts into formal intake. */
        public boolean isRehearsal() {
            return rehearsal == null || rehearsal;
        }
    }
}
