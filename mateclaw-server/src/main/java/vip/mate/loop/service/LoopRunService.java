package vip.mate.loop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import vip.mate.loop.dto.LoopRunCreateRequest;
import vip.mate.loop.dto.LoopRunExecuteResponse;
import vip.mate.loop.dto.LoopRunResponse;
import vip.mate.loop.model.LoopRunEntity;
import vip.mate.loop.model.SuperpowerDefinition;
import vip.mate.loop.repository.LoopRunMapper;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class LoopRunService {

    private final LoopRunMapper loopRunMapper;
    private final SuperpowerRegistryService registryService;
    private final LoopCommandExecutor commandExecutor;
    private final ObjectMapper objectMapper;

    public LoopRunResponse createRun(long workspaceId, LoopRunCreateRequest request) {
        LoopRunCreateRequest safeRequest = request == null
                ? new LoopRunCreateRequest(null, null, null, null, null, null, null, null, null, null)
                : request;
        SuperpowerDefinition superpower = resolveSuperpower(workspaceId, safeRequest);

        LoopRunEntity run = new LoopRunEntity();
        run.setWorkspaceId(workspaceId);
        run.setSuperpowerSkillId(superpower.skillId());
        run.setSuperpowerName(superpower.name());
        run.setSuperpowerVersion(superpower.version());
        run.setDomain(superpower.domain());
        run.setScenario(superpower.scenario());
        run.setStatus("planned");
        run.setInputJson(toJson(buildInputPayload(safeRequest)));
        run.setStepResultsJson("[]");
        run.setArtifactsJson("[]");
        run.setFinalReportJson(toJson(Map.of(
                "status", "planned",
                "message", "Loop run is planned. Execute it to create an isolated workspace and run the verification command.",
                "humanGateRequired", true
        )));
        run.setDeleted(0);
        loopRunMapper.insert(run);
        return LoopRunResponse.from(run);
    }

    public LoopRunResponse getRun(long workspaceId, long runId) {
        return LoopRunResponse.from(loadRun(workspaceId, runId));
    }

    public LoopRunExecuteResponse execute(long workspaceId, long runId) {
        LoopRunEntity run = loadRun(workspaceId, runId);
        SuperpowerDefinition superpower = registryService.findBySkillId(workspaceId, run.getSuperpowerSkillId())
                .orElseGet(() -> registryService.findByDomainAndScenario(workspaceId, run.getDomain(), run.getScenario())
                        .orElseThrow(() -> new IllegalArgumentException("Superpower for loop run is no longer available")));

        LocalDateTime startedAt = LocalDateTime.now();
        run.setStatus("running");
        run.setStartedAt(startedAt);
        run.setCompletedAt(null);
        run.setFinalReportJson(toJson(Map.of(
                "status", "running",
                "message", "Loop verification command is running.",
                "humanGateRequired", true
        )));
        loopRunMapper.updateById(run);

        LoopCommandExecutor.LoopExecutionOutcome outcome = commandExecutor.execute(run.getId(), run.getInputJson(), superpower);
        run.setStatus(outcome.status());
        run.setStepResultsJson(outcome.stepResultsJson());
        run.setArtifactsJson(outcome.artifactsJson());
        run.setFinalReportJson(outcome.finalReportJson());
        run.setCompletedAt(LocalDateTime.now());
        loopRunMapper.updateById(run);
        return new LoopRunExecuteResponse(
                LoopRunResponse.from(run),
                outcome.message()
        );
    }

    private LoopRunEntity loadRun(long workspaceId, long runId) {
        LoopRunEntity run = loopRunMapper.selectOne(new LambdaQueryWrapper<LoopRunEntity>()
                .eq(LoopRunEntity::getWorkspaceId, workspaceId)
                .eq(LoopRunEntity::getId, runId)
                .eq(LoopRunEntity::getDeleted, 0));
        if (run == null) {
            throw new IllegalArgumentException("Loop run not found: " + runId);
        }
        return run;
    }

    private SuperpowerDefinition resolveSuperpower(long workspaceId, LoopRunCreateRequest request) {
        if (request.superpowerSkillId() != null) {
            return registryService.findBySkillId(workspaceId, request.superpowerSkillId())
                    .orElseThrow(() -> new IllegalArgumentException("Superpower skill not found: " + request.superpowerSkillId()));
        }
        if (StringUtils.hasText(request.domain()) && StringUtils.hasText(request.scenario())) {
            return registryService.findByDomainAndScenario(workspaceId, request.domain(), request.scenario())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Superpower not found: " + request.domain() + "/" + request.scenario()));
        }
        return registryService.listSuperpowers(workspaceId).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No Loop Engineering superpowers are enabled"));
    }

    private Map<String, Object> buildInputPayload(LoopRunCreateRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        putIfPresent(payload, "repoPath", request.repoPath());
        putIfPresent(payload, "command", request.command());
        putIfPresent(payload, "repairCommand", request.repairCommand());
        putIfPresent(payload, "goal", request.goal());
        putIfPresent(payload, "branch", request.branch());
        putIfPresent(payload, "externalCaseId", request.externalCaseId());
        if (request.input() != null && !request.input().isEmpty()) {
            payload.put("input", request.input());
        }
        return payload;
    }

    private static void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (StringUtils.hasText(value)) payload.put(key, value);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize loop run payload", e);
        }
    }
}
