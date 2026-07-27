package vip.mate.troubleshooting.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.troubleshooting.evidence.EvidenceSourceHealth;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

/** Read-only capability surface for evidence-source readiness. */
@RestController
@RequestMapping("/api/v1/troubleshooting/evidence")
@RequiredArgsConstructor
public class EvidenceSourceController {

    private final EvidenceSourceRouter router;

    /** Does not probe or query a source; returns its current fail-closed readiness snapshot. */
    @GetMapping("/sources")
    @RequireWorkspaceRole("viewer")
    public R<List<EvidenceSourceHealth>> sources() {
        return R.ok(router.health());
    }
}
