package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
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
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.ObservabilityAssetCatalogView;
import vip.mate.troubleshooting.evidence.ObservabilityAssetDeclaration;
import vip.mate.troubleshooting.evidence.ObservabilityAssetService;
import vip.mate.troubleshooting.evidence.ObservabilityAssetView;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.Map;

/** Maintains exact system-to-observability associations without exposing source secrets. */
@RestController
@RequestMapping("/api/v1/troubleshooting/evidence/assets")
@RequiredArgsConstructor
public class ObservabilityAssetController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final ObservabilityAssetService assets;

    @GetMapping
    @RequireWorkspaceRole("viewer")
    public R<ObservabilityAssetCatalogView> catalog(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(assets.catalog(resolveWorkspace(workspaceId)));
    }

    /** Inserts one immutable revision. Only workspace admins may change source scope. */
    @PutMapping
    @RequireWorkspaceRole("admin")
    public R<ObservabilityAssetView> declare(
            @Valid @RequestBody ObservabilityAssetRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(assets.declare(
                resolveWorkspace(workspaceId),
                new ObservabilityAssetDeclaration(
                        request.system(),
                        request.service(),
                        request.displayName(),
                        request.platform(),
                        request.environment(),
                        request.region(),
                        request.cluster(),
                        request.namespace(),
                        request.enabled(),
                        request.signalBindings(),
                        request.parameters(),
                        request.expectedVersion(),
                        request.reason()),
                currentActor()));
    }

    public record ObservabilityAssetRequest(
            @NotBlank @Size(max = 128) String system,
            @NotBlank @Size(max = 128) String service,
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Size(max = 64) String platform,
            @NotBlank @Size(max = 256) String environment,
            @Size(max = 256) String region,
            @Size(max = 256) String cluster,
            @Size(max = 256) String namespace,
            boolean enabled,
            @Size(max = 32) Map<@NotBlank @Size(max = 128) String,
                    @NotBlank @Size(max = 128) String> signalBindings,
            @Size(max = 64) Map<@NotBlank @Size(max = 64) String,
                    @NotBlank @Size(max = 256) String> parameters,
            @PositiveOrZero Integer expectedVersion,
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
                    "declaring an observability asset requires an authenticated operator");
        }
        return authentication.getName();
    }
}
