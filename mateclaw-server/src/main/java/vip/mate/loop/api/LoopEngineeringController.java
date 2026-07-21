package vip.mate.loop.api;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.loop.dto.LoopRunCreateRequest;
import vip.mate.loop.dto.LoopRunExecuteResponse;
import vip.mate.loop.dto.LoopRunResponse;
import vip.mate.loop.dto.LoopSuperpowerPreviewRequest;
import vip.mate.loop.dto.LoopSuperpowerPreviewResponse;
import vip.mate.loop.dto.LoopSuperpowerSummary;
import vip.mate.loop.service.LoopRunService;
import vip.mate.loop.service.SuperpowerRegistryService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/loop-engineering")
public class LoopEngineeringController {

    private final SuperpowerRegistryService registryService;
    private final LoopRunService loopRunService;

    @GetMapping("/superpowers")
    @RequireWorkspaceRole("member")
    public R<List<LoopSuperpowerSummary>> listSuperpowers(@RequestHeader("X-Workspace-Id") long workspaceId) {
        return R.ok(registryService.listSuperpowers(workspaceId).stream()
                .map(LoopSuperpowerSummary::from)
                .toList());
    }

    @PostMapping("/superpowers/preview")
    @RequireWorkspaceRole("member")
    public R<LoopSuperpowerPreviewResponse> previewSuperpower(
            @RequestHeader("X-Workspace-Id") long workspaceId,
            @RequestBody(required = false) LoopSuperpowerPreviewRequest request) {
        return R.ok(registryService.preview(workspaceId, request));
    }

    @PostMapping("/runs")
    @RequireWorkspaceRole("member")
    public R<LoopRunResponse> createRun(@RequestHeader("X-Workspace-Id") long workspaceId,
                                        @RequestBody(required = false) LoopRunCreateRequest request) {
        return R.ok(loopRunService.createRun(workspaceId, request));
    }

    @GetMapping("/runs/{runId}")
    @RequireWorkspaceRole("member")
    public R<LoopRunResponse> getRun(@RequestHeader("X-Workspace-Id") long workspaceId,
                                     @PathVariable long runId) {
        return R.ok(loopRunService.getRun(workspaceId, runId));
    }

    @PostMapping("/runs/{runId}/execute")
    @RequireWorkspaceRole("member")
    public R<LoopRunExecuteResponse> executeRun(@RequestHeader("X-Workspace-Id") long workspaceId,
                                                @PathVariable long runId) {
        return R.ok(loopRunService.execute(workspaceId, runId));
    }
}
