package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.EvidenceContractTrialRequest;
import vip.mate.troubleshooting.evidence.EvidenceContractTrialService;
import vip.mate.troubleshooting.evidence.EvidenceContractTrialView;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

/** Admin-only read-only trials and viewer-safe immutable audit history. */
@RestController
@RequestMapping("/api/v1/troubleshooting/evidence/contract-trials")
@RequiredArgsConstructor
public class EvidenceContractTrialController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;
    private final EvidenceContractTrialService service;

    @PostMapping
    @RequireWorkspaceRole("admin")
    public R<EvidenceContractTrialView> run(
            @Valid @RequestBody EvidenceContractTrialRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(service.run(resolveWorkspace(workspaceId), request, currentActor()));
    }

    @GetMapping
    @RequireWorkspaceRole("viewer")
    public R<List<EvidenceContractTrialView>> list(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(required = false) @Size(max = 128) String system,
            @RequestParam(name = "service", required = false) @Size(max = 128)
            String serviceName,
            @RequestParam(required = false) @Size(max = 128) String contractRef,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) Integer limit) {
        return R.ok(service.list(resolveWorkspace(workspaceId), system, serviceName,
                contractRef, limit));
    }

    private long resolveWorkspace(Long workspaceId) {
        return workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId;
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new MateClawException(
                    "err.troubleshooting.actor_required", 401,
                    "running an evidence contract trial requires an authenticated operator");
        }
        return authentication.getName();
    }
}
