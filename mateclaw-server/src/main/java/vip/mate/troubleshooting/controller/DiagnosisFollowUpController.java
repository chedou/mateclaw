package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

/** Diagnosis-bound deterministic questions and immutable supplemental runs. */
@RestController
@RequestMapping("/api/v1/troubleshooting/diagnoses/{diagnosisId}")
@RequiredArgsConstructor
public class DiagnosisFollowUpController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;
    private final DiagnosisFollowUpService service;

    @PostMapping("/follow-ups")
    @RequireWorkspaceRole("member")
    public R<DiagnosisFollowUpResult> followUp(
            @PathVariable String diagnosisId,
            @Valid @RequestBody FollowUpRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(service.respond(
                workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId,
                diagnosisId,
                request.text(),
                currentActor()));
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

    public record FollowUpRequest(@NotBlank @Size(max = 4000) String text) { }
}
