package vip.mate.troubleshooting.evaluation;

import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.agent.ApprovedEvidenceSpineCatalog;
import vip.mate.troubleshooting.evidence.EvidenceProperties;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.evidence.RecordedReplayAdapter;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Fail-closed server capability check for the Replay capture button. */
@Service
public final class RecordedReplayEvaluationCapabilityService {

    private static final String PLATFORM = "recorded-replay";
    private static final Pattern EVALUATION_SCENARIO_KEY =
            Pattern.compile("[a-z][a-z0-9_:-]{1,63}");

    private final EvidenceSourceRouter router;
    private final RecordedReplayAdapter adapter;
    private final EvidenceProperties properties;
    private final ApprovedEvidenceSpineCatalog catalog;
    private final TroubleshootingPersistenceService persistence;

    public RecordedReplayEvaluationCapabilityService(
            EvidenceSourceRouter router,
            RecordedReplayAdapter adapter,
            EvidenceProperties properties,
            ApprovedEvidenceSpineCatalog catalog,
            TroubleshootingPersistenceService persistence) {
        this.router = router;
        this.adapter = adapter;
        this.properties = properties;
        this.catalog = catalog;
        this.persistence = persistence;
    }

    public RecordedReplayEvaluationCapability inspect(
            long workspaceId,
            String diagnosisId) {
        if (workspaceId <= 0 || blank(diagnosisId)) {
            return RecordedReplayEvaluationCapability.unavailable(
                    "INVALID_SCOPE",
                    "Replay 能力检查缺少 Workspace 或 Diagnosis");
        }
        EvidenceProperties.SynthesisPreview scope = properties.getSynthesisPreview();
        if (scope == null || workspaceId != scope.getFixtureWorkspaceId()) {
            return RecordedReplayEvaluationCapability.unavailable(
                    "SCOPE_NOT_REGISTERED",
                    "当前 Workspace 不在 Replay fixture 登记范围");
        }

        StoredDiagnosis stored;
        try {
            stored = persistence.get(workspaceId, diagnosisId.trim());
        } catch (MateClawException missingDiagnosis) {
            return RecordedReplayEvaluationCapability.unavailable(
                    "DIAGNOSIS_NOT_AVAILABLE",
                    "当前 Diagnosis 不存在或不属于该 Workspace");
        }
        Diagnosis diagnosis = stored == null ? null : stored.diagnosis();
        IncidentContext incident = diagnosis == null ? null : diagnosis.incident();
        if (incident == null || blank(incident.system()) || blank(incident.service())) {
            return RecordedReplayEvaluationCapability.unavailable(
                    "DIAGNOSIS_SCOPE_INVALID",
                    "当前 Diagnosis 没有可登记的系统与服务范围");
        }
        String system = incident.system().trim();
        String service = incident.service().trim();
        if (!registered(scope.getFixtureServices(), system, service)) {
            return RecordedReplayEvaluationCapability.unavailable(
                    "SCOPE_NOT_REGISTERED",
                    "当前 Workspace / 系统 / 服务不在 Replay fixture 登记范围");
        }
        if (!router.canRoute(workspaceId, system, "log_search", PLATFORM)
                || !router.canRoute(workspaceId, system, "log_trace_bundle", PLATFORM)) {
            return RecordedReplayEvaluationCapability.unavailable(
                    "ADAPTER_NOT_READY",
                    "Recorded Replay 默认关闭，或核心 Evidence Spine 路由尚未就绪");
        }

        List<ApprovedEvidenceSpineCatalog.ApprovedSpinePlan> targets = new ArrayList<>();
        for (String scenarioKey : catalog.visibleScenarioKeys(workspaceId, incident)) {
            try {
                ApprovedEvidenceSpineCatalog.ApprovedSpinePlan plan =
                        catalog.resolve(workspaceId, incident, scenarioKey);
                if (plan.permittedPlatforms().contains(PLATFORM)
                        && adapter.hasCoreFixture(
                                system,
                                service,
                                plan.evidencePlan().searchTerm())) {
                    targets.add(plan);
                }
            } catch (IllegalArgumentException staleCatalogEntry) {
                // Configuration changed between list and resolve; fail closed below.
            }
        }
        if (targets.isEmpty()) {
            return RecordedReplayEvaluationCapability.unavailable(
                    "FIXTURE_NOT_FOUND",
                    "当前 Diagnosis 没有同时通过目录、平台和 fixture 校验的 Replay 目标");
        }
        if (targets.size() > 1) {
            return RecordedReplayEvaluationCapability.unavailable(
                    "FIXTURE_TARGET_AMBIGUOUS",
                    "当前 Diagnosis 匹配多个 Replay 目标，必须先收敛服务端目录");
        }
        ApprovedEvidenceSpineCatalog.ApprovedSpinePlan target = targets.get(0);
        if (!EVALUATION_SCENARIO_KEY.matcher(target.scenarioKey()).matches()) {
            return RecordedReplayEvaluationCapability.unavailable(
                    "FIXTURE_TARGET_INVALID",
                    "已批准 Replay 场景键不符合 T8 结构化样本合同");
        }
        return RecordedReplayEvaluationCapability.ready(
                target.scenarioKey(),
                target.evidencePlan().searchTerm(),
                target.evidencePlan().window());
    }

    private boolean registered(
            Map<String, List<String>> scopes,
            String system,
            String service) {
        return scopes != null && scopes.entrySet().stream()
                .filter(entry -> entry.getKey() != null
                        && entry.getKey().equalsIgnoreCase(system.trim()))
                .map(Map.Entry::getValue)
                .filter(values -> values != null)
                .flatMap(List::stream)
                .anyMatch(value -> value != null
                        && value.equalsIgnoreCase(service.trim()));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
