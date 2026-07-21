package vip.mate.loop.dto;

import java.util.Map;

public record LoopRunCreateRequest(
        Long superpowerSkillId,
        String domain,
        String scenario,
        String repoPath,
        String command,
        String repairCommand,
        String goal,
        String branch,
        String externalCaseId,
        Map<String, Object> input
) {
}
