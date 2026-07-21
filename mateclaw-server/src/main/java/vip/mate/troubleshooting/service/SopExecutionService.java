package vip.mate.troubleshooting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.dto.SopRunCompleteResponse;
import vip.mate.troubleshooting.dto.SopRunStartResponse;
import vip.mate.troubleshooting.dto.SopRouteCandidate;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.dto.SopRouteResult;
import vip.mate.troubleshooting.dto.SopStepResult;
import vip.mate.troubleshooting.dto.SopValidationResult;
import vip.mate.troubleshooting.dto.SopSummary;
import vip.mate.troubleshooting.model.SopDefinition;
import vip.mate.troubleshooting.model.TroubleshootingSopRunEntity;
import vip.mate.troubleshooting.repository.TroubleshootingSopRunMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SopExecutionService {

    private final TroubleshootingSopRunMapper runMapper;
    private final SopRegistryService registryService;
    private final SopRouter router;
    private final SopValidator validator;
    private final SopReportRenderer reportRenderer;
    private final SopExecutionPromptBuilder promptBuilder;
    private final SopRunDraftBuilder draftBuilder;
    private final ObjectMapper objectMapper;

    public SopRunStartResponse startRun(long workspaceId, String caseId, SopRouteRequest alert) {
        SopRouteResult routeResult = router.route(workspaceId, alert);
        TroubleshootingSopRunEntity run = createRun(workspaceId, caseId, routeResult, alert);
        SopDefinition sop = registryService.findBySkillId(workspaceId, run.getSopSkillId())
                .orElseThrow(() -> new MateClawException("SOP not found: " + run.getSopSkillId()));
        return new SopRunStartResponse(
                run,
                routeResult,
                SopSummary.from(sop),
                promptBuilder.build(run, sop, alert, routeResult),
                draftBuilder.buildStepDrafts(sop, alert, routeResult),
                draftBuilder.buildFinalReportDraft(sop, alert, routeResult)
        );
    }

    public TroubleshootingSopRunEntity createRun(long workspaceId, String caseId, SopRouteResult routeResult,
                                                 SopRouteRequest alert) {
        if (caseId == null || caseId.isBlank()) {
            throw new MateClawException("caseId is required");
        }
        SopRouteCandidate selected = routeResult == null ? null : routeResult.selected();
        if (selected == null) {
            throw new MateClawException("No SOP selected");
        }
        TroubleshootingSopRunEntity row = new TroubleshootingSopRunEntity();
        row.setWorkspaceId(workspaceId);
        row.setCaseId(caseId);
        row.setSopSkillId(selected.skillId());
        row.setSopName(selected.name());
        row.setSopVersion(selected.version());
        row.setDomain(selected.domain());
        row.setScenario(selected.scenario());
        row.setConfidence(selected.confidence());
        row.setStatus("running");
        row.setRouteReason(selected.reason());
        row.setAlertJson(toJson(alert == null ? Map.of() : alert));
        row.setStartedAt(LocalDateTime.now());
        row.setDeleted(0);
        runMapper.insert(row);
        return row;
    }

    public TroubleshootingSopRunEntity completeRun(long workspaceId, Long runId,
                                                   List<SopStepResult> steps,
                                                   Map<String, Object> finalReport) {
        return completeRunWithReport(workspaceId, runId, steps, finalReport).run();
    }

    public SopRunCompleteResponse completeRunWithReport(long workspaceId, Long runId,
                                                        List<SopStepResult> steps,
                                                        Map<String, Object> finalReport) {
        TroubleshootingSopRunEntity row = getRun(workspaceId, runId);
        SopDefinition sop = registryService.findBySkillId(workspaceId, row.getSopSkillId())
                .orElseThrow(() -> new MateClawException("SOP not found: " + row.getSopSkillId()));
        SopValidationResult validation = validator.validate(sop, steps);
        String groupReport = reportRenderer.renderGroupReport(sop, validation, finalReport);
        row.setStepResultsJson(toJson(steps));
        row.setFinalReportJson(toJson(Map.of(
                "groupReport", groupReport,
                "raw", finalReport == null ? Map.of() : finalReport
        )));
        row.setValidationErrorsJson(toJson(validation));
        row.setStatus(isEvidenceInsufficient(validation, steps, finalReport) ? "evidence_insufficient" : "succeeded");
        row.setCompletedAt(LocalDateTime.now());
        runMapper.updateById(row);
        return new SopRunCompleteResponse(row, validation, groupReport);
    }

    public List<TroubleshootingSopRunEntity> listByCase(long workspaceId, String caseId) {
        return runMapper.selectList(new LambdaQueryWrapper<TroubleshootingSopRunEntity>()
                .eq(TroubleshootingSopRunEntity::getWorkspaceId, workspaceId)
                .eq(TroubleshootingSopRunEntity::getCaseId, caseId)
                .eq(TroubleshootingSopRunEntity::getDeleted, 0)
                .orderByDesc(TroubleshootingSopRunEntity::getCreateTime));
    }

    public List<SopStepResult> parseStepResults(TroubleshootingSopRunEntity run) {
        if (run == null || run.getStepResultsJson() == null || run.getStepResultsJson().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(run.getStepResultsJson(), new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private TroubleshootingSopRunEntity getRun(long workspaceId, Long runId) {
        if (runId == null) throw new MateClawException("runId is required");
        TroubleshootingSopRunEntity row = runMapper.selectById(runId);
        if (row == null || row.getWorkspaceId() == null || row.getWorkspaceId() != workspaceId
                || Integer.valueOf(1).equals(row.getDeleted())) {
            throw new MateClawException("SOP run not found: " + runId);
        }
        return row;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new MateClawException("Failed to serialize SOP run payload: " + e.getMessage());
        }
    }

    private static boolean isEvidenceInsufficient(SopValidationResult validation,
                                                  List<SopStepResult> steps,
                                                  Map<String, Object> finalReport) {
        if (validation == null || !validation.valid()) {
            return true;
        }
        if (finalReport != null) {
            Object confidence = finalReport.get("confidence");
            if (confidence != null && "low".equalsIgnoreCase(confidence.toString())) {
                return true;
            }
            Object conclusion = finalReport.get("conclusion");
            if (conclusion != null && conclusion.toString().contains("证据不足")) {
                return true;
            }
        }
        return steps != null && !steps.isEmpty()
                && steps.stream().allMatch(step -> "inconclusive".equalsIgnoreCase(step.status()));
    }
}
