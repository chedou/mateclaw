package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.EvidenceRouteService;
import vip.mate.troubleshooting.evidence.EvidenceRouteView;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

/**
 * 让一个 workspace 自己声明取证路由，而不必改发布物里的 YAML 再重新发版。
 *
 * <p>请求体只能说「按什么顺序问哪几个平台」。端点与凭据始终只在运维配置的适配器
 * 里，所以调用方是在**已启用的源之间做选择**，无法引入一个新的源。</p>
 */
@RestController
@RequestMapping("/api/v1/troubleshooting/evidence/routes")
@RequiredArgsConstructor
public class EvidenceRouteController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final EvidenceRouteService routes;

    @GetMapping
    @RequireWorkspaceRole("viewer")
    public R<List<EvidenceRouteView>> list(
            @RequestParam(required = false) String system,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(routes.list(resolveWorkspace(workspaceId), system));
    }

    /**
     * Declares or replaces exactly one route.
     *
     * <p>要 admin：路由决定一条取证请求打到哪个**生产观测系统**。</p>
     */
    @PutMapping
    @RequireWorkspaceRole("admin")
    public R<EvidenceRouteView> declare(
            @Valid @RequestBody EvidenceRouteRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(routes.declare(
                resolveWorkspace(workspaceId),
                request.system(),
                request.signalKind(),
                request.platforms(),
                currentActor(),
                request.reason()));
    }

    /** Withdraws one declaration so the deployment-level route applies again. */
    @DeleteMapping
    @RequireWorkspaceRole("admin")
    public R<Void> withdraw(
            @RequestParam String system,
            @RequestParam String signalKind,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        routes.withdraw(resolveWorkspace(workspaceId), system, signalKind);
        return R.ok();
    }

    /**
     * 请求体带不了 actor：谁改的取自鉴权，不接受提交方自称。
     *
     * <p>{@code platforms} 允许为空列表——那是「这一格明确不取证」，与「没声明过」
     * 是两个不同的答案，后者会回落到部署级配置。</p>
     */
    public record EvidenceRouteRequest(
            @NotBlank @Size(max = 128) String system,
            @NotBlank @Size(max = 128) String signalKind,
            List<@NotBlank @Size(max = 64) String> platforms,
            @NotBlank @Size(max = 500) String reason) {
    }

    private long resolveWorkspace(Long workspaceId) {
        return workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId;
    }

    private String currentActor() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new MateClawException(
                    "err.troubleshooting.actor_required", 401,
                    "declaring an evidence route requires an authenticated operator");
        }
        return authentication.getName();
    }
}
