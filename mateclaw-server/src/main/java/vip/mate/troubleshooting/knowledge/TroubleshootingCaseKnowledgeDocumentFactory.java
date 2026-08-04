package vip.mate.troubleshooting.knowledge;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.TroubleshootingBusinessTextPolicy;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.service.StoredDiagnosis;

import java.util.Locale;

/**
 * Builds a bounded case snapshot from facts already persisted on a Diagnosis.
 * Raw queries, observed payloads, credentials and model reasoning are excluded.
 */
@Component
public class TroubleshootingCaseKnowledgeDocumentFactory {

    private static final int TEXT_LIMIT = 500;

    public TroubleshootingCaseKnowledgeDocument create(StoredDiagnosis stored) {
        if (stored == null) {
            throw new IllegalArgumentException("stored diagnosis is required");
        }
        Diagnosis diagnosis = stored.diagnosis();
        boolean authoritative = isAuthoritativeResolution(diagnosis);
        String title = safe("排障案例 · " + diagnosis.caseId() + " · "
                + diagnosis.incident().system() + "/" + diagnosis.incident().service(), 240);
        String summary = authoritative
                ? safe("已验证解决案例：" + diagnosis.incident().title(), 300)
                : safe("调查记录（不作为根因依据）："
                        + diagnosis.incident().title(), 300);
        String markdown = markdown(stored, authoritative, title);
        return new TroubleshootingCaseKnowledgeDocument(
                diagnosis.diagnosisId(),
                diagnosis.caseId(),
                stored.version(),
                slug(diagnosis.diagnosisId(), stored.version()),
                title,
                summary,
                markdown,
                authoritative);
    }

    private String markdown(StoredDiagnosis stored, boolean authoritative, String title) {
        Diagnosis diagnosis = stored.diagnosis();
        StringBuilder out = new StringBuilder(2048);
        out.append("# ").append(title).append("\n\n")
                .append("> 知识级别：")
                .append(authoritative ? "已验证解决案例" : "调查记录（不作为根因依据）")
                .append("\n> 来源：MateClaw Diagnosis ")
                .append(safe(diagnosis.diagnosisId(), 160))
                .append(" · 聚合版本 ").append(stored.version()).append("\n\n")
                .append("## 故障现象\n\n")
                .append("- 系统 / 服务：")
                .append(safe(diagnosis.incident().system(), 128)).append(" / ")
                .append(safe(diagnosis.incident().service(), 128)).append("\n")
                .append("- 严重级别：").append(safe(diagnosis.incident().severity(), 16)).append("\n")
                .append("- 现象：").append(safe(diagnosis.incident().title(), TEXT_LIMIT)).append("\n")
                .append("- 影响：").append(safe(diagnosis.incident().impact().note(), TEXT_LIMIT)).append("\n")
                .append("- 发生时间：").append(diagnosis.incident().occurredAt()).append("\n")
                .append("- 排障单 / 案例 / 运行：")
                .append(safe(diagnosis.diagnosisId(), 160)).append(" / ")
                .append(safe(diagnosis.caseId(), 160)).append(" / ")
                .append(safe(diagnosis.runId(), 160)).append("\n\n")
                .append("## 调查路径\n\n")
                .append("- 调查模式：").append(diagnosis.investigationMode()).append("\n")
                .append("- 路由权威：").append(diagnosis.routeAuthority()).append("\n")
                .append("- 排查指南选择器：")
                .append(safe(nullable(diagnosis.sopKey()), 200)).append("\n")
                .append("- 冻结版本：").append(playbookRef(diagnosis.sourcePlaybookVersionRef())).append("\n")
                .append("- 案例状态：").append(diagnosis.status()).append("\n")
                .append("- 结论类型 / 信心：")
                .append(diagnosis.conclusionType()).append(" / ").append(diagnosis.confidence()).append("\n")
                .append("- 数据性质：")
                .append(diagnosis.fixtureMode() ? "Recorded Replay 回放" : "非回放数据")
                .append("\n\n")
                .append("## 证据引用\n\n");

        if (diagnosis.evidence().isEmpty()) {
            out.append("- 未记录\n");
        } else {
            for (EvidenceResult evidence : diagnosis.evidence()) {
                // Deliberately exports neither evidence.query nor evidence.observed.
                out.append("- `").append(safe(evidence.queryId(), 160)).append("` · ")
                        .append(safe(evidence.namespace(), 80)).append(" · ")
                        .append(evidence.status()).append(" · ")
                        .append(safe(evidence.source(), 80)).append("：")
                        .append(safe(evidence.summary(), TEXT_LIMIT)).append("\n");
            }
        }

        out.append("\n## 结论\n\n");
        if (authoritative) {
            out.append("- 已验证根因：")
                    .append(safe(diagnosis.rootCause(), TEXT_LIMIT)).append("\n")
                    .append("- 诊断摘要：")
                    .append(safe(diagnosis.summary(), TEXT_LIMIT)).append("\n");
        } else {
            out.append("- 未形成可复用结论。当前根因字段不写入知识结论。\n")
                    .append("- 可用于检索相似现象和调查路径，不可用于直接定根因。\n");
        }

        out.append("\n## 处置闭环\n\n");
        if (diagnosis.closure() == null) {
            out.append("- 未关闭；未记录恢复验证。\n");
        } else {
            out.append("- 结果：").append(diagnosis.closure().outcome()).append("\n")
                    .append("- 恢复已验证：")
                    .append(diagnosis.closure().recoveryVerified() ? "是" : "否").append("\n")
                    .append("- 闭环记录：")
                    .append(safe(diagnosis.closure().summary(), TEXT_LIMIT)).append("\n")
                    .append("- 关闭时间：").append(diagnosis.closure().closedAt()).append("\n");
        }

        out.append("\n## 使用边界\n\n")
                .append("- 该文档由已持久化事实确定性生成，不包含模型思维过程。\n")
                .append("- 未写入原始日志、查询语句、观测载荷或凭据。\n")
                .append("- 该文档不授权任何生产变更；处置仍需人工审核。\n");
        return out.toString();
    }

    private boolean isAuthoritativeResolution(Diagnosis diagnosis) {
        return diagnosis.status() == DiagnosisStatus.CLOSED
                && diagnosis.closure() != null
                && diagnosis.closure().outcome() == ClosureOutcome.RECOVERED
                && diagnosis.closure().recoveryVerified()
                && !diagnosis.rehearsal()
                && !diagnosis.fixtureMode()
                && !diagnosis.abstained();
    }

    private String playbookRef(PlaybookVersionRef ref) {
        return ref == null ? "未记录" : safe(ref.playbookId(), 160) + "@" + ref.playbookVersion();
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? "未记录" : value;
    }

    private String safe(String value, int maxChars) {
        return TroubleshootingBusinessTextPolicy.forChannel(value, maxChars);
    }

    private String slug(String diagnosisId, int version) {
        String normalized = diagnosisId.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (normalized.isBlank()) {
            normalized = Integer.toUnsignedString(diagnosisId.hashCode(), 36);
        }
        return "troubleshooting-case-" + normalized + "-v" + version;
    }
}
