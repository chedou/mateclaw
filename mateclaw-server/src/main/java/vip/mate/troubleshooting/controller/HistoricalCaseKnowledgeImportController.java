package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.troubleshooting.knowledge.HistoricalCaseKnowledgeImportResult;
import vip.mate.troubleshooting.knowledge.HistoricalCaseKnowledgeImportService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/** Admin-owned backfill of prior Diagnosis snapshots into an existing Wiki vector base. */
@RestController
@RequestMapping("/api/v1/troubleshooting/knowledge/case-imports")
@RequiredArgsConstructor
public class HistoricalCaseKnowledgeImportController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final HistoricalCaseKnowledgeImportService service;

    @PostMapping
    @RequireWorkspaceRole("admin")
    public R<HistoricalCaseKnowledgeImportResult> importCases(
            @Valid @RequestBody HistoricalCaseKnowledgeImportRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(service.importCases(
                workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId,
                request.knowledgeBaseId(),
                request.resolvedLimit()));
    }
}
