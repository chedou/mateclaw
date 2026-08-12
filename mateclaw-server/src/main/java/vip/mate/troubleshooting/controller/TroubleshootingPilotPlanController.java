package vip.mate.troubleshooting.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.troubleshooting.pilot.TroubleshootingPilotPlanService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

/** Workspace administration endpoint for the first-wave troubleshooting pilot. */
@RestController
@RequestMapping("/api/v1/troubleshooting/pilot-plan")
@RequiredArgsConstructor
public class TroubleshootingPilotPlanController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final TroubleshootingPilotPlanService pilotPlans;

    @GetMapping
    @RequireWorkspaceRole("viewer")
    public R<TroubleshootingPilotPlanService.PlanView> current(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(pilotPlans.current(resolveWorkspace(workspaceId)));
    }

    @PutMapping
    @RequireWorkspaceRole("admin")
    public R<TroubleshootingPilotPlanService.PlanView> declare(
            @RequestBody DeclareRequest body,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        TroubleshootingPilotPlanService.Declaration declaration = body == null
                ? null
                : new TroubleshootingPilotPlanService.Declaration(
                        body.name(), body.modules(), requireId(body.secondLineUserId()),
                        requireId(body.thirdLineUserId()), requireId(body.sourceOwnerUserId()),
                        Boolean.TRUE.equals(body.enabled()),
                        body.expectedVersion() == null ? -1 : body.expectedVersion(),
                        body.reason());
        return R.ok(pilotPlans.declare(
                resolveWorkspace(workspaceId), declaration, currentActor()));
    }

    private long requireId(Long userId) {
        return userId == null ? 0L : userId;
    }

    private long resolveWorkspace(Long workspaceId) {
        return workspaceId == null || workspaceId <= 0 ? DEFAULT_WORKSPACE_ID : workspaceId;
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return "unknown";
        }
        return authentication.getName();
    }

    public record DeclareRequest(
            String name,
            List<TroubleshootingPilotPlanService.ModuleScope> modules,
            Long secondLineUserId,
            Long thirdLineUserId,
            Long sourceOwnerUserId,
            Boolean enabled,
            Integer expectedVersion,
            String reason) {
    }
}
