package vip.mate.troubleshooting.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.troubleshooting.agent.OpenDiscoveryAgentBindingService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/**
 * Binds a workspace digital employee (Agent) as the OPEN_DISCOVERY executor.
 */
@RestController
@RequestMapping("/api/v1/troubleshooting/open-discovery")
@RequiredArgsConstructor
public class OpenDiscoveryAgentBindingController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final OpenDiscoveryAgentBindingService bindingService;

    @GetMapping("/agent-binding")
    @RequireWorkspaceRole("viewer")
    public R<OpenDiscoveryAgentBindingService.BindingView> current(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(bindingService.current(resolveWorkspace(workspaceId)));
    }

    @PutMapping("/agent-binding")
    @RequireWorkspaceRole("admin")
    public R<OpenDiscoveryAgentBindingService.BindingView> bind(
            @RequestBody BindRequest body,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long agentId = body == null || body.agentId() == null ? 0L : body.agentId();
        boolean prepare = body != null && Boolean.TRUE.equals(body.prepareEvidenceTool());
        return R.ok(bindingService.bind(
                resolveWorkspace(workspaceId),
                agentId,
                currentActor(),
                prepare));
    }

    @DeleteMapping("/agent-binding")
    @RequireWorkspaceRole("admin")
    public R<OpenDiscoveryAgentBindingService.BindingView> clear(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(bindingService.clear(resolveWorkspace(workspaceId)));
    }

    private long resolveWorkspace(Long workspaceId) {
        return workspaceId == null || workspaceId <= 0 ? DEFAULT_WORKSPACE_ID : workspaceId;
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            return "unknown";
        }
        return authentication.getName();
    }

    public record BindRequest(Long agentId, Boolean prepareEvidenceTool) {
    }
}
