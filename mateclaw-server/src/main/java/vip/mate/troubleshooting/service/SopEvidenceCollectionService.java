package vip.mate.troubleshooting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.dto.SopEvidenceCollectRequest;
import vip.mate.troubleshooting.dto.SopEvidenceCollectResponse;
import vip.mate.troubleshooting.dto.SopEvidenceRecord;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.dto.SopStepResult;
import vip.mate.troubleshooting.evidence.CollectedEvidence;
import vip.mate.troubleshooting.evidence.EvidenceCollectionRequest;
import vip.mate.troubleshooting.evidence.EvidenceConnector;
import vip.mate.troubleshooting.model.SopDefinition;
import vip.mate.troubleshooting.model.TroubleshootingEvidenceEntity;
import vip.mate.troubleshooting.model.TroubleshootingSopRunEntity;
import vip.mate.troubleshooting.repository.TroubleshootingEvidenceMapper;
import vip.mate.troubleshooting.repository.TroubleshootingSopRunMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SopEvidenceCollectionService {

    private final TroubleshootingSopRunMapper runMapper;
    private final TroubleshootingEvidenceMapper evidenceMapper;
    private final SopRegistryService registryService;
    private final List<EvidenceConnector> connectors;
    private final ObjectMapper objectMapper;

    public SopEvidenceCollectResponse collectForRun(long workspaceId, Long runId, SopEvidenceCollectRequest request) {
        TroubleshootingSopRunEntity run = getRun(workspaceId, runId);
        SopDefinition sop = registryService.findBySkillId(workspaceId, run.getSopSkillId())
                .orElseThrow(() -> new MateClawException("SOP not found: " + run.getSopSkillId()));
        SopRouteRequest alert = parseAlert(run);
        List<String> evidenceTypes = resolveEvidenceTypes(sop, request);
        if (evidenceTypes.isEmpty()) {
            throw new MateClawException("No evidence types requested");
        }

        evidenceMapper.delete(new LambdaQueryWrapper<TroubleshootingEvidenceEntity>()
                .eq(TroubleshootingEvidenceEntity::getWorkspaceId, workspaceId)
                .eq(TroubleshootingEvidenceEntity::getRunId, runId)
                .in(TroubleshootingEvidenceEntity::getEvidenceType, evidenceTypes));

        List<TroubleshootingEvidenceEntity> entities = new ArrayList<>();
        for (String evidenceType : evidenceTypes) {
            EvidenceConnector connector = findConnector(evidenceType);
            EvidenceCollectionRequest collectRequest = new EvidenceCollectionRequest(
                    workspaceId,
                    run.getCaseId(),
                    run,
                    sop,
                    alert,
                    evidenceType
            );
            List<CollectedEvidence> collected = connector.collect(collectRequest);
            for (int i = 0; i < collected.size(); i++) {
                TroubleshootingEvidenceEntity entity = toEntity(
                        workspaceId,
                        run,
                        normalize(evidenceType),
                        connector,
                        collected.get(i),
                        i + 1
                );
                evidenceMapper.insert(entity);
                entities.add(entity);
            }
        }

        List<SopEvidenceRecord> records = entities.stream().map(SopEvidenceRecord::from).toList();
        return new SopEvidenceCollectResponse(
                run,
                records,
                toStepResults(evidenceTypes, records),
                finalReportTemplate(sop, records)
        );
    }

    public List<SopEvidenceRecord> listEvidence(long workspaceId, Long runId) {
        getRun(workspaceId, runId);
        return evidenceMapper.selectList(new LambdaQueryWrapper<TroubleshootingEvidenceEntity>()
                        .eq(TroubleshootingEvidenceEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingEvidenceEntity::getRunId, runId)
                        .eq(TroubleshootingEvidenceEntity::getDeleted, 0)
                        .orderByAsc(TroubleshootingEvidenceEntity::getEvidenceType)
                        .orderByAsc(TroubleshootingEvidenceEntity::getCreateTime))
                .stream()
                .map(SopEvidenceRecord::from)
                .toList();
    }

    private TroubleshootingEvidenceEntity toEntity(long workspaceId,
                                                   TroubleshootingSopRunEntity run,
                                                   String requestedType,
                                                   EvidenceConnector connector,
                                                   CollectedEvidence collected,
                                                   int index) {
        String evidenceType = normalize(value(collected.evidenceType(), requestedType));
        TroubleshootingEvidenceEntity entity = new TroubleshootingEvidenceEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setCaseId(run.getCaseId());
        entity.setRunId(run.getId());
        entity.setEvidenceId("E-" + run.getId() + "-" + evidenceType.toUpperCase(Locale.ROOT) + "-" + String.format("%03d", index));
        entity.setEvidenceType(evidenceType);
        entity.setSource(value(collected.source(), connector.id()));
        entity.setStatus(value(collected.status(), "collected"));
        entity.setTitle(collected.title());
        entity.setSummary(collected.summary());
        entity.setContentJson(toJson(collected.content() == null ? Map.of() : collected.content()));
        entity.setCollectedAt(LocalDateTime.now());
        entity.setDeleted(0);
        return entity;
    }

    private List<SopStepResult> toStepResults(List<String> evidenceTypes, List<SopEvidenceRecord> records) {
        Map<String, List<SopEvidenceRecord>> byType = new LinkedHashMap<>();
        for (String evidenceType : evidenceTypes) {
            byType.put(normalize(evidenceType), new ArrayList<>());
        }
        for (SopEvidenceRecord record : records) {
            byType.computeIfAbsent(normalize(record.evidenceType()), k -> new ArrayList<>()).add(record);
        }

        List<SopStepResult> steps = new ArrayList<>();
        for (Map.Entry<String, List<SopEvidenceRecord>> entry : byType.entrySet()) {
            String type = entry.getKey();
            List<SopEvidenceRecord> values = entry.getValue();
            String status = stepStatus(values);
            List<String> evidenceIds = values.stream().map(SopEvidenceRecord::evidenceId).toList();
            List<String> insights = evidenceInsights(type, values);
            String observation = values.isEmpty()
                    ? "未采集到 " + type + " 证据"
                    : join(joined(values.stream().map(SopEvidenceRecord::summary).toList(), insights));
            String interpretation = values.isEmpty()
                    ? "缺少该证据时不能形成确定根因。"
                    : "已通过 " + join(values.stream().map(SopEvidenceRecord::source).distinct().toList())
                    + " 采集 " + type + " 证据；" + evidenceInterpretation(type, values)
                    + "；MVP 阶段若来源为 mock，需要替换真实连接器后复核。";
            steps.add(new SopStepResult(
                    "collect-" + type,
                    status,
                    evidenceIds,
                    List.of(type),
                    observation,
                    interpretation,
                    "continue"
            ));
        }
        return steps;
    }

    private String stepStatus(List<SopEvidenceRecord> values) {
        if (values.isEmpty()) return "inconclusive";
        if (values.stream().anyMatch(v -> "failed".equalsIgnoreCase(v.status()))) return "failed";
        if (values.stream().anyMatch(v -> !"collected".equalsIgnoreCase(v.status()))) return "inconclusive";
        return "passed";
    }

    private Map<String, Object> finalReportTemplate(SopDefinition sop, List<SopEvidenceRecord> records) {
        Map<String, Object> report = new LinkedHashMap<>();
        long mockCount = records.stream().filter(r -> "mock-troubleshooting".equalsIgnoreCase(r.source())).count();
        boolean allMock = !records.isEmpty() && mockCount == records.size();
        LinkedHashSet<String> required = new LinkedHashSet<>();
        if (sop != null && sop.requiredEvidence() != null) {
            sop.requiredEvidence().stream()
                    .map(SopEvidenceCollectionService::normalize)
                    .filter(v -> !v.isBlank())
                    .forEach(required::add);
        }
        long requiredMockCount = records.stream()
                .filter(r -> required.contains(normalize(r.evidenceType())))
                .filter(r -> "mock-troubleshooting".equalsIgnoreCase(r.source()))
                .count();
        boolean requiredStillMock = requiredMockCount > 0;
        report.put("conclusion", allMock
                ? "MVP 模拟证据已覆盖必查项：需要替换真实连接器后确认根因"
                : requiredStillMock
                ? "已采集部分真实证据，但必查项仍包含模拟证据：需要替换真实连接器后确认根因"
                : "已采集 SOP 必查证据，请结合 stepResults 判断根因");
        report.put("confidence", allMock || requiredStillMock ? "medium" : "high");
        report.put("nextAction", allMock || requiredStillMock
                ? "接入真实 metrics/logs/release/synthetics 连接器，或人工补充真实 evidence 后重新提交"
                : "复核关键证据并同步群内摘要");
        report.put("domain", sop == null ? null : sop.domain());
        report.put("scenario", sop == null ? null : sop.scenario());
        report.put("evidenceCount", records.size());
        report.put("sources", records.stream().map(SopEvidenceRecord::source).distinct().toList());
        Map<String, List<String>> evidenceSignals = evidenceSignals(records);
        if (!evidenceSignals.isEmpty()) {
            report.put("evidenceSignals", evidenceSignals);
        }
        return report;
    }

    private List<String> evidenceInsights(String type, List<SopEvidenceRecord> records) {
        List<String> insights = new ArrayList<>();
        for (SopEvidenceRecord record : records) {
            Map<String, Object> normalized = normalizedContent(record);
            if ("synthetics".equals(type)) {
                addString(insights, normalized.get("availabilityConclusion"));
                addSignalList(insights, normalized.get("diagnosisSignals"), 4);
                continue;
            }
            if ("metrics".equals(type)) {
                addSignalList(insights, normalized.get("anomalyHints"), 4);
                continue;
            }
            if ("host".equals(type) || "container".equals(type) || "k8s".equals(type)) {
                addString(insights, normalized.get("infrastructureConclusion"));
                addSignalList(insights, normalized.get("infrastructureSignals"), 4);
                addSignalList(insights, normalized.get("abnormalStates"), 2);
            }
        }
        return insights.stream()
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .limit(8)
                .toList();
    }

    private String evidenceInterpretation(String type, List<SopEvidenceRecord> records) {
        List<String> insights = evidenceInsights(type, records);
        if (insights.isEmpty()) {
            return "当前证据已入库，需结合 SOP 判断标准复核。";
        }
        return switch (type) {
            case "synthetics" -> "拨测归一化结论：" + join(insights);
            case "metrics" -> "指标异常信号：" + join(insights);
            case "host", "container", "k8s" -> "基础设施异常信号：" + join(insights);
            default -> "归一化信号：" + join(insights);
        };
    }

    private Map<String, List<String>> evidenceSignals(List<SopEvidenceRecord> records) {
        Map<String, List<String>> signals = new LinkedHashMap<>();
        Map<String, List<SopEvidenceRecord>> byType = new LinkedHashMap<>();
        for (SopEvidenceRecord record : records) {
            byType.computeIfAbsent(normalize(record.evidenceType()), k -> new ArrayList<>()).add(record);
        }
        for (Map.Entry<String, List<SopEvidenceRecord>> entry : byType.entrySet()) {
            List<String> insights = evidenceInsights(entry.getKey(), entry.getValue());
            if (!insights.isEmpty()) signals.put(entry.getKey(), insights);
        }
        return signals;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizedContent(SopEvidenceRecord record) {
        try {
            if (record == null || record.contentJson() == null || record.contentJson().isBlank()) return Map.of();
            Object parsed = objectMapper.readValue(record.contentJson(), Object.class);
            if (!(parsed instanceof Map<?, ?> content)) return Map.of();
            Object normalized = content.get("normalized");
            return normalized instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static void addSignalList(List<String> out, Object value, int limit) {
        if (!(value instanceof List<?> list)) return;
        list.stream()
                .filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(String::valueOf)
                .limit(limit)
                .forEach(out::add);
    }

    private static void addString(List<String> out, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            out.add(String.valueOf(value));
        }
    }

    private EvidenceConnector findConnector(String evidenceType) {
        return connectors.stream()
                .filter(connector -> connector.supports(evidenceType))
                .min(Comparator.comparingInt(EvidenceConnector::order))
                .orElseThrow(() -> new MateClawException("No evidence connector supports type: " + evidenceType));
    }

    private List<String> resolveEvidenceTypes(SopDefinition sop, SopEvidenceCollectRequest request) {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        if (request != null && request.evidenceTypes() != null && !request.evidenceTypes().isEmpty()) {
            request.evidenceTypes().stream()
                    .map(SopEvidenceCollectionService::normalize)
                    .filter(v -> !v.isBlank())
                    .forEach(types::add);
            return new ArrayList<>(types);
        }
        if (sop != null && sop.requiredEvidence() != null) {
            sop.requiredEvidence().stream()
                    .map(SopEvidenceCollectionService::normalize)
                    .filter(v -> !v.isBlank())
                    .forEach(types::add);
        }
        if (Boolean.TRUE.equals(request == null ? null : request.includeOptional())
                && sop != null && sop.optionalEvidence() != null) {
            sop.optionalEvidence().stream()
                    .map(SopEvidenceCollectionService::normalize)
                    .filter(v -> !v.isBlank())
                    .forEach(types::add);
        }
        return new ArrayList<>(types);
    }

    private SopRouteRequest parseAlert(TroubleshootingSopRunEntity run) {
        if (run.getAlertJson() == null || run.getAlertJson().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(run.getAlertJson(), SopRouteRequest.class);
        } catch (Exception e) {
            return null;
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
            throw new MateClawException("Failed to serialize evidence payload: " + e.getMessage());
        }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (List.of("synthetic", "synthetics", "dialtest", "dial-test", "dial_test", "guance-synthetics", "拨测")
                .contains(normalized)) {
            return "synthetics";
        }
        String underscored = normalized.replace('-', '_');
        if (List.of("hosts", "infra_host", "infrastructure_host", "guance_host", "guance_hosts", "主机")
                .contains(underscored)) {
            return "host";
        }
        if (List.of("containers", "pod", "pods", "infra_container", "infrastructure_container",
                "guance_container", "guance_containers", "guance_pod", "guance_pods", "容器")
                .contains(underscored)) {
            return "container";
        }
        if (List.of("kubernetes", "guance_k8s").contains(underscored)) {
            return "k8s";
        }
        return normalized;
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String join(List<String> values) {
        return String.join("；", values.stream()
                .filter(v -> v != null && !v.isBlank())
                .toList());
    }

    private static List<String> joined(List<String> first, List<String> second) {
        List<String> values = new ArrayList<>(first == null ? List.of() : first);
        if (second != null) values.addAll(second);
        return values;
    }
}
