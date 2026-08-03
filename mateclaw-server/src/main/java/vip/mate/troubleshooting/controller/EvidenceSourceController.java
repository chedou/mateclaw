package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.EvidenceSourceHealth;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptanceService;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptanceView;
import vip.mate.troubleshooting.evidence.GuanceEvidenceReadiness;
import vip.mate.troubleshooting.evidence.GuanceEvidenceReadinessService;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreviewService;
import vip.mate.troubleshooting.evidence.GuanceEvidenceValidationReport;
import vip.mate.troubleshooting.evidence.GuanceEvidenceValidationService;
import vip.mate.troubleshooting.evidence.GuanceRecordingTargetCatalog;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

/** Read-only capability surface for evidence-source readiness. */
@RestController
@RequestMapping("/api/v1/troubleshooting/evidence")
@RequiredArgsConstructor
public class EvidenceSourceController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final EvidenceSourceRouter router;
    private final GuanceEvidenceReadinessService readinessService;
    private final GuanceEvidenceValidationService validationService;
    private final GuanceEvidenceSpinePreviewService spinePreviewService;
    private final GuanceEvidenceAcceptanceService acceptanceService;
    private final vip.mate.troubleshooting.evidence.EvidenceSourceAcceptanceService
            sourceAcceptanceService;
    private final GuanceRecordingTargetCatalog recordingTargetCatalog;

    /** Does not probe or query a source; returns its current fail-closed readiness snapshot. */
    @GetMapping("/sources")
    @RequireWorkspaceRole("viewer")
    public R<List<EvidenceSourceHealth>> sources() {
        return R.ok(router.health());
    }

    /**
     * Returns the exact workspace/system/service binding gate without probing
     * Guance or exposing source configuration material.
     */
    @GetMapping("/readiness")
    @RequireWorkspaceRole("viewer")
    public R<GuanceEvidenceReadiness> readiness(
            @RequestParam String system,
            @RequestParam String service,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(readinessService.inspect(
                resolveWorkspace(workspaceId), system, service));
    }

    /**
     * Returns the server-frozen, unrecorded targets that match this running
     * Guance binding. It does not query Guance or expose DQL.
     */
    @GetMapping("/guance/recording-targets")
    @RequireWorkspaceRole("viewer")
    public R<GuanceRecordingTargetCatalog.View> guanceRecordingTargets(
            @RequestParam String system,
            @RequestParam String service,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        GuanceEvidenceReadiness readiness = readinessService.inspect(
                resolveWorkspace(workspaceId), system, service);
        return R.ok(recordingTargetCatalog.inspect(readiness));
    }

    /**
     * Runs one Guance-only canonical chain. It is read-only and non-persistent,
     * but admin-gated because it consumes a live observability API.
     */
    @PostMapping("/guance/validate")
    @RequireWorkspaceRole("admin")
    public R<GuanceEvidenceValidationReport> validateGuance(
            @Valid @RequestBody GuanceEvidenceValidationRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(validationService.validate(
                resolveWorkspace(workspaceId),
                request.system(),
                request.service(),
                request.searchTerm(),
                request.window(),
                request.occurredAt()));
    }

    /**
     * Runs the shared three-stage Evidence Spine against Guance only and returns
     * a bounded deterministic projection. It never persists evidence, creates a
     * candidate, or changes the fixture/T7/T8 acceptance state.
     */
    @PostMapping("/guance/spine/preview")
    @RequireWorkspaceRole("admin")
    public R<GuanceEvidenceSpinePreview> previewGuanceSpine(
            @Valid @RequestBody GuanceEvidenceValidationRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(spinePreviewService.preview(
                resolveWorkspace(workspaceId),
                request.system(),
                request.service(),
                request.searchTerm(),
                request.window(),
                request.occurredAt()));
    }

    /**
     * 平台无关的证据源验收状态（Prometheus / Elasticsearch 等）。
     *
     * <p>与下面的 Guance 专用接口并存而不是取代它：V184 已经承载了真实验收记录，
     * 把它迁走是另一件事，不该混在加一个适配器里做。</p>
     */
    @GetMapping("/sources/{platform}/acceptance")
    @RequireWorkspaceRole("viewer")
    public R<vip.mate.troubleshooting.evidence.EvidenceSourceAcceptanceView> sourceAcceptance(
            @PathVariable String platform,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(sourceAcceptanceService.inspect(resolveWorkspace(workspaceId), platform));
    }

    /**
     * 记录一次 owner 验收。
     *
     * <p>请求体里**只有清单**。指纹由适配器算、验证事实由服务端自己重跑一次只读
     * 取证得到、actor 取自鉴权上下文——任何一项若接受提交方传入，验收就退化成一句
     * 可以随手写下的声明。</p>
     */
    @PostMapping("/sources/{platform}/acceptance")
    @RequireWorkspaceRole("owner")
    public R<vip.mate.troubleshooting.evidence.EvidenceSourceAcceptanceView> acceptSource(
            @PathVariable String platform,
            @Valid @RequestBody EvidenceSourceAcceptanceRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(sourceAcceptanceService.accept(
                resolveWorkspace(workspaceId), platform, request.toChecklist(), currentActor()));
    }

    /** Returns whether an owner accepted the exact current binding fingerprint. */
    @GetMapping("/guance/acceptance")
    @RequireWorkspaceRole("viewer")
    public R<GuanceEvidenceAcceptanceView> guanceAcceptance(
            @RequestParam String system,
            @RequestParam String service,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(acceptanceService.inspect(
                resolveWorkspace(workspaceId), system, service));
    }

    /**
     * Re-runs the two-stage Guance chain and records an explicit owner
     * attestation for the exact current binding fingerprint.
     */
    @PostMapping("/guance/acceptance")
    @RequireWorkspaceRole("owner")
    public R<GuanceEvidenceAcceptanceView> acceptGuance(
            @Valid @RequestBody GuanceEvidenceAcceptanceRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(acceptanceService.accept(
                resolveWorkspace(workspaceId),
                request.system(),
                request.service(),
                request.searchTerm(),
                request.window(),
                request.occurredAt(),
                request.checklist(),
                currentActor()));
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
                    "err.troubleshooting.actor_required",
                    401,
                    "Guance owner acceptance requires an authenticated operator");
        }
        return authentication.getName();
    }
}
