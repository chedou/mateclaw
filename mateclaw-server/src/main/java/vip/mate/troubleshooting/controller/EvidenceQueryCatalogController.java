package vip.mate.troubleshooting.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.troubleshooting.evidence.EvidenceQueryCatalogService;
import vip.mate.troubleshooting.evidence.EvidenceQueryCatalogView;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/** Secret-free maintenance catalog for reviewed evidence query contracts. */
@RestController
@RequestMapping("/api/v1/troubleshooting/evidence/catalog")
@RequiredArgsConstructor
public class EvidenceQueryCatalogController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final EvidenceQueryCatalogService catalogService;

    @GetMapping
    @RequireWorkspaceRole("viewer")
    public R<EvidenceQueryCatalogView> catalog(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(catalogService.inspect(
                workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId));
    }
}
