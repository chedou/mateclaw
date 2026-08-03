package vip.mate.troubleshooting.service;

import org.springframework.stereotype.Service;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.InvestigationProvenance;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.RouteMode;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Assembles 「这次调查动用了什么、没动用什么」 from the frozen record.
 *
 * <p>Every fact here already existed; none of them was in one place. The
 * assembly happens server-side because a console that stitches four shapes
 * together is a console that can stitch them together wrongly, and a provenance
 * view that is subtly wrong is worse than none.</p>
 */
@Service
public class InvestigationProvenanceService {

    /** Adapters report this when nothing could be asked at all. */
    private static final String UNKNOWN_ADAPTER = "未知（取证接缝未记录来源）";

    private final TroubleshootingPersistenceService persistence;
    private final TroubleshootingPlaybookVersionService playbookVersions;

    public InvestigationProvenanceService(
            TroubleshootingPersistenceService persistence,
            TroubleshootingPlaybookVersionService playbookVersions) {
        this.persistence = persistence;
        this.playbookVersions = playbookVersions;
    }

    public InvestigationProvenance explain(long workspaceId, String diagnosisId) {
        Diagnosis diagnosis = persistence.get(workspaceId, diagnosisId).diagnosis();
        return new InvestigationProvenance(
                diagnosis.diagnosisId(),
                knowledge(workspaceId, diagnosis),
                collectors(diagnosis),
                reasoning(diagnosis),
                abstentions(diagnosis, knowledge(workspaceId, diagnosis)));
    }

    private InvestigationProvenance.Knowledge knowledge(
            long workspaceId, Diagnosis diagnosis) {
        PlaybookVersionRef ref = diagnosis.sourcePlaybookVersionRef();
        if (ref == null) {
            return new InvestigationProvenance.Knowledge(
                    diagnosis.sopKey(), diagnosis.sopTitle(), null, null,
                    diagnosis.sourcePlaybookOwner(), null, false, false,
                    "这条诊断没有冻结 Playbook 版本；无法说明当时用的是哪一份知识。");
        }
        ApprovedPlaybookVersion version =
                playbookVersions.findByRef(workspaceId, ref).orElse(null);
        if (version == null) {
            return new InvestigationProvenance.Knowledge(
                    diagnosis.sopKey(), diagnosis.sopTitle(),
                    ref.playbookId(), ref.playbookVersion(),
                    diagnosis.sourcePlaybookOwner(), null, false, false,
                    "冻结的 Playbook 版本已读不到；不拿当前版本冒充当时那一份。");
        }
        return new InvestigationProvenance.Knowledge(
                version.selectorKey(),
                version.playbook().title(),
                version.playbookId(),
                version.playbookVersion(),
                version.playbook().ownerTeam(),
                // 手写夹具与真实归纳在注册表里平级；在用它下结论的地方，
                // 这个区别最要紧（T0.9 问的正是这件事）。
                version.sourceOrigin(),
                version.playbook().operational(),
                true,
                null);
    }

    private List<InvestigationProvenance.Collector> collectors(Diagnosis diagnosis) {
        Set<String> cited = new HashSet<>(diagnosis.evidenceCitations());
        // The error-code path carries no citation list; the model path and the
        // scenario evidence-arrival transition do. Where there is none, every
        // row reports null rather than false — "本路径不维护引用清单" and
        // "这条证据没有支撑结论" are different claims.
        boolean citationsTracked = !cited.isEmpty();
        List<InvestigationProvenance.Collector> collectors = new ArrayList<>();
        for (EvidenceResult result : diagnosis.evidence()) {
            boolean answered = result.status() != EvidenceStatus.MISSING;
            collectors.add(new InvestigationProvenance.Collector(
                    result.queryId(),
                    result.namespace(),
                    // 空来源本身就是一个发现：连问了谁都没记下来。
                    result.source().isBlank() ? UNKNOWN_ADAPTER : result.source(),
                    result.status(),
                    answered,
                    citationsTracked ? cited.contains(result.queryId()) : null,
                    result.collectedAt()));
        }
        return collectors;
    }

    private InvestigationProvenance.Reasoning reasoning(Diagnosis diagnosis) {
        boolean modelInvoked = diagnosis.routeMode() == RouteMode.LLM_FALLBACK;
        return new InvestigationProvenance.Reasoning(
                diagnosis.routeMode(),
                diagnosis.investigationMode(),
                diagnosis.routeAuthority(),
                diagnosis.conclusionType(),
                modelInvoked,
                // 只读 Agent 的具体模型版本不在聚合里；说"有模型参与但未记录型号"
                // 比留空诚实，留空会被读成"没有模型参与"。
                modelInvoked ? "只读 Agent（模型型号未记入本聚合）" : null,
                diagnosis.triggeredSignals().size(),
                diagnosis.sourcePlaybookVersionRef() != null);
    }

    /**
     * Hand-authored knowledge, whatever the registry calls it.
     *
     * <p>Being filed under {@code recordedEvidenceSeeds} does not make a
     * Playbook's numbers recorded — several of those were authored by hand and
     * the seeder marks them {@code MANUAL}. The marking is the fact; the
     * container name is not.</p>
     */
    private boolean handWritten(InvestigationProvenance.Knowledge knowledge) {
        String origin = knowledge.origin();
        return origin != null
                && origin.toUpperCase(java.util.Locale.ROOT).startsWith("MANUAL");
    }

    /**
     * The negatives. Each one is a mechanism a reviewer can go and check, not a
     * reassurance — "没有执行器" is falsifiable, "很安全" is not.
     */
    private List<InvestigationProvenance.Abstention> abstentions(
            Diagnosis diagnosis,
            InvestigationProvenance.Knowledge knowledge) {
        List<InvestigationProvenance.Abstention> abstentions = new ArrayList<>();
        if (diagnosis.routeMode() == RouteMode.DETERMINISTIC) {
            abstentions.add(new InvestigationProvenance.Abstention(
                    "大模型",
                    "确定性路径全程零模型调用；结论来自 Playbook 写好的判据与规则。"));
        }
        abstentions.add(new InvestigationProvenance.Abstention(
                "Skills / Tools 注册表",
                "排障链路不经过平台的 skills 与 tools 注册表；取证只走证据适配器。"));
        if (!diagnosis.writeExecutionEnabled()) {
            abstentions.add(new InvestigationProvenance.Abstention(
                    "生产写执行器",
                    "平台没有生产写执行器；人工批准只推进状态机，变更由人在平台之外完成。"));
        }
        abstentions.add(new InvestigationProvenance.Abstention(
                "写操作",
                "本次取证全部为只读查询，未对任何被观测系统发起写入。"));
        if (diagnosis.fixtureMode()) {
            abstentions.add(new InvestigationProvenance.Abstention(
                    "真实观测源",
                    "本次证据来自 fixture / 录制回放，未接入真实观测云；"
                            + "回放通过不等于真实数据已验证（A10）。"));
        }
        // 证据是真的 ≠ 知识是真的。这两件事此前被压在同一个 fixtureMode 布尔值上。
        // 那个全局常量已经删掉，成色改为从每批证据自己身上推导；但危险没有消失，
        // 只是换了形状：T7 真源接通那天，fixtureMode 会**自动**变 false，而那些由
        // 手写 Playbook 路由、阈值从没用真实历史故障标定过的诊断，会跟着一起变成
        // 「真源」。所以这一条**不看 fixtureMode**，只看知识本身的来源。
        if (handWritten(knowledge)) {
            abstentions.add(new InvestigationProvenance.Abstention(
                    "真实数据校准",
                    "这份 Playbook 的判据与阈值是人手写的，从未用真实历史故障标定过。"
                            + "证据是否来自真源、与知识是否被真实数据校准，是两件事；"
                            + "接上真源不会让手写阈值变得可信。"));
        } else if (!knowledge.readable()) {
            abstentions.add(new InvestigationProvenance.Abstention(
                    "知识来源判定",
                    "读不到冻结的 Playbook 版本，无法判断这份知识是手写还是归纳产出；"
                            + "在判定出来之前，不要把它当作已校准的知识。"));
        }
        if (diagnosis.rehearsal()) {
            abstentions.add(new InvestigationProvenance.Abstention(
                    "正式流程",
                    "这是一次演练，不进入处置流程。"));
        }
        return abstentions;
    }
}
