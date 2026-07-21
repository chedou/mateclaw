package vip.mate.loop.dto;

import vip.mate.loop.model.LoopRunEntity;

import java.time.LocalDateTime;

public record LoopRunResponse(
        Long id,
        Long workspaceId,
        Long superpowerSkillId,
        String superpowerName,
        String superpowerVersion,
        String domain,
        String scenario,
        String status,
        String inputJson,
        String stepResultsJson,
        String artifactsJson,
        String finalReportJson,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
    public static LoopRunResponse from(LoopRunEntity run) {
        return new LoopRunResponse(
                run.getId(),
                run.getWorkspaceId(),
                run.getSuperpowerSkillId(),
                run.getSuperpowerName(),
                run.getSuperpowerVersion(),
                run.getDomain(),
                run.getScenario(),
                run.getStatus(),
                run.getInputJson(),
                run.getStepResultsJson(),
                run.getArtifactsJson(),
                run.getFinalReportJson(),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getCreateTime(),
                run.getUpdateTime()
        );
    }
}
