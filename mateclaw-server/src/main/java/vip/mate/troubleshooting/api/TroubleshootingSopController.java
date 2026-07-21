package vip.mate.troubleshooting.api;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.troubleshooting.dto.SopEvidenceCollectRequest;
import vip.mate.troubleshooting.dto.SopEvidenceCollectResponse;
import vip.mate.troubleshooting.dto.SopEvidenceRecord;
import vip.mate.troubleshooting.dto.SopRunCompleteRequest;
import vip.mate.troubleshooting.dto.SopRunCompleteResponse;
import vip.mate.troubleshooting.dto.SopRunStartResponse;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.dto.SopRouteResult;
import vip.mate.troubleshooting.dto.SopSummary;
import vip.mate.troubleshooting.dto.TroubleshootingConnectorConfigRequest;
import vip.mate.troubleshooting.dto.TroubleshootingConnectorConfigResponse;
import vip.mate.troubleshooting.dto.TroubleshootingQueryTemplatePreviewRequest;
import vip.mate.troubleshooting.dto.TroubleshootingQueryTemplatePreviewResponse;
import vip.mate.troubleshooting.dto.TroubleshootingQueryTemplateRequest;
import vip.mate.troubleshooting.dto.TroubleshootingQueryTemplateResponse;
import vip.mate.troubleshooting.model.TroubleshootingSopRunEntity;
import vip.mate.troubleshooting.service.SopEvidenceCollectionService;
import vip.mate.troubleshooting.service.SopExecutionService;
import vip.mate.troubleshooting.service.SopRegistryService;
import vip.mate.troubleshooting.service.SopRouter;
import vip.mate.troubleshooting.service.TroubleshootingConnectorConfigService;
import vip.mate.troubleshooting.service.TroubleshootingQueryTemplatePreviewService;
import vip.mate.troubleshooting.service.TroubleshootingQueryTemplateService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/troubleshooting")
public class TroubleshootingSopController {

    private final SopRegistryService registryService;
    private final SopRouter router;
    private final SopExecutionService executionService;
    private final SopEvidenceCollectionService evidenceCollectionService;
    private final TroubleshootingQueryTemplateService queryTemplateService;
    private final TroubleshootingQueryTemplatePreviewService queryTemplatePreviewService;
    private final TroubleshootingConnectorConfigService connectorConfigService;

    @GetMapping("/sops")
    @RequireWorkspaceRole("member")
    public R<List<SopSummary>> listSops(@RequestHeader("X-Workspace-Id") long workspaceId) {
        return R.ok(registryService.listSops(workspaceId).stream()
                .map(SopSummary::from)
                .toList());
    }

    @PostMapping("/sops/preview-route")
    @RequireWorkspaceRole("member")
    public R<SopRouteResult> previewRoute(@RequestHeader("X-Workspace-Id") long workspaceId,
                                          @RequestBody(required = false) SopRouteRequest request) {
        return R.ok(router.route(workspaceId, request));
    }

    @GetMapping("/cases/{caseId}/sop-runs")
    @RequireWorkspaceRole("member")
    public R<List<TroubleshootingSopRunEntity>> listCaseRuns(@RequestHeader("X-Workspace-Id") long workspaceId,
                                                             @PathVariable String caseId) {
        return R.ok(executionService.listByCase(workspaceId, caseId));
    }

    @PostMapping("/cases/{caseId}/sop-runs")
    @RequireWorkspaceRole("member")
    public R<SopRunStartResponse> createCaseRun(@RequestHeader("X-Workspace-Id") long workspaceId,
                                                @PathVariable String caseId,
                                                @RequestBody(required = false) SopRouteRequest request) {
        return R.ok(executionService.startRun(workspaceId, caseId, request));
    }

    @PostMapping("/sop-runs/{runId}/complete")
    @RequireWorkspaceRole("member")
    public R<SopRunCompleteResponse> completeRun(@RequestHeader("X-Workspace-Id") long workspaceId,
                                                 @PathVariable Long runId,
                                                 @RequestBody(required = false) SopRunCompleteRequest request) {
        return R.ok(executionService.completeRunWithReport(
                workspaceId,
                runId,
                request == null ? List.of() : request.stepResults(),
                request == null ? null : request.finalReport()
        ));
    }

    @PostMapping("/sop-runs/{runId}/collect-evidence")
    @RequireWorkspaceRole("member")
    public R<SopEvidenceCollectResponse> collectEvidence(@RequestHeader("X-Workspace-Id") long workspaceId,
                                                         @PathVariable Long runId,
                                                         @RequestBody(required = false) SopEvidenceCollectRequest request) {
        return R.ok(evidenceCollectionService.collectForRun(workspaceId, runId, request));
    }

    @GetMapping("/sop-runs/{runId}/evidence")
    @RequireWorkspaceRole("member")
    public R<List<SopEvidenceRecord>> listEvidence(@RequestHeader("X-Workspace-Id") long workspaceId,
                                                   @PathVariable Long runId) {
        return R.ok(evidenceCollectionService.listEvidence(workspaceId, runId));
    }

    @GetMapping("/query-templates")
    @RequireWorkspaceRole("admin")
    public R<List<TroubleshootingQueryTemplateResponse>> listQueryTemplates(
            @RequestHeader("X-Workspace-Id") long workspaceId,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String evidenceType) {
        return R.ok(queryTemplateService.list(workspaceId, provider, evidenceType).stream()
                .map(TroubleshootingQueryTemplateResponse::from)
                .toList());
    }

    @GetMapping("/connectors/guance")
    @RequireWorkspaceRole("admin")
    public R<TroubleshootingConnectorConfigResponse> getGuanceConnectorConfig(
            @RequestHeader("X-Workspace-Id") long workspaceId) {
        return R.ok(connectorConfigService.getGuanceConfig(workspaceId));
    }

    @PutMapping("/connectors/guance")
    @RequireWorkspaceRole("admin")
    public R<TroubleshootingConnectorConfigResponse> saveGuanceConnectorConfig(
            @RequestHeader("X-Workspace-Id") long workspaceId,
            @RequestBody TroubleshootingConnectorConfigRequest request) {
        return R.ok(connectorConfigService.saveGuanceConfig(workspaceId, request));
    }

    @PostMapping("/query-templates")
    @RequireWorkspaceRole("admin")
    public R<TroubleshootingQueryTemplateResponse> createQueryTemplate(
            @RequestHeader("X-Workspace-Id") long workspaceId,
            @RequestBody TroubleshootingQueryTemplateRequest request) {
        return R.ok(TroubleshootingQueryTemplateResponse.from(
                queryTemplateService.create(workspaceId, request)));
    }

    @PostMapping("/query-templates/guance/defaults")
    @RequireWorkspaceRole("admin")
    public R<List<TroubleshootingQueryTemplateResponse>> seedGuanceDefaultTemplates(
            @RequestHeader("X-Workspace-Id") long workspaceId) {
        return R.ok(queryTemplateService.seedGuanceDefaultTemplates(workspaceId).stream()
                .map(TroubleshootingQueryTemplateResponse::from)
                .toList());
    }

    @PostMapping("/query-templates/preview")
    @RequireWorkspaceRole("admin")
    public R<TroubleshootingQueryTemplatePreviewResponse> previewQueryTemplate(
            @RequestHeader("X-Workspace-Id") long workspaceId,
            @RequestBody TroubleshootingQueryTemplatePreviewRequest request) {
        return R.ok(queryTemplatePreviewService.preview(workspaceId, request));
    }

    @PutMapping("/query-templates/{id}")
    @RequireWorkspaceRole("admin")
    public R<TroubleshootingQueryTemplateResponse> updateQueryTemplate(
            @RequestHeader("X-Workspace-Id") long workspaceId,
            @PathVariable long id,
            @RequestBody TroubleshootingQueryTemplateRequest request) {
        return R.ok(TroubleshootingQueryTemplateResponse.from(
                queryTemplateService.update(workspaceId, id, request)));
    }

    @DeleteMapping("/query-templates/{id}")
    @RequireWorkspaceRole("admin")
    public R<Void> deleteQueryTemplate(@RequestHeader("X-Workspace-Id") long workspaceId,
                                       @PathVariable long id) {
        queryTemplateService.delete(workspaceId, id);
        return R.ok();
    }
}
