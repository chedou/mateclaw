package vip.mate.troubleshooting.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.troubleshooting.evidence.GuanceRecordingBatchReadiness;
import vip.mate.troubleshooting.evidence.GuanceRecordingBatchReadinessService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/** V2 workspace-level read projection for the immutable first T7 batch. */
@RestController
@RequestMapping("/api/v2/troubleshooting/evidence/guance/recording-batches")
public class GuanceRecordingBatchController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final GuanceRecordingBatchReadinessService readinessService;

    public GuanceRecordingBatchController(
            GuanceRecordingBatchReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    /** Does not query Guance and never creates targets, samples or acceptance. */
    @GetMapping("/current")
    @RequireWorkspaceRole("viewer")
    public R<GuanceRecordingBatchReadiness> current(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(readinessService.inspect(
                workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId));
    }
}
