package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.troubleshooting.evidence.EvidenceSourceHealth;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.evidence.GuanceEvidenceReadiness;
import vip.mate.troubleshooting.evidence.GuanceEvidenceReadinessService;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreviewService;
import vip.mate.troubleshooting.evidence.GuanceEvidenceValidationReport;
import vip.mate.troubleshooting.evidence.GuanceEvidenceValidationService;
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

    private long resolveWorkspace(Long workspaceId) {
        return workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId;
    }
}
