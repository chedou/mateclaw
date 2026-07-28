package vip.mate.troubleshooting.projection;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.CanonicalNumberParser;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.evidence.CanonicalEvidenceSchema;
import vip.mate.troubleshooting.model.BlastRadius;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentImpact;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.CallChainView;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.ContrastView;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.Hop;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection.ImpactView;
import vip.mate.troubleshooting.synthesis.DeterministicLogTraceCompressor;
import vip.mate.troubleshooting.synthesis.LogTraceSkeleton;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maps already-stored canonical evidence into bounded experience facts.
 *
 * <p>This projector performs no IO and introduces no parallel evidence model.
 * Missing or malformed facts remain explicit instead of being inferred from UI
 * text.</p>
 */
@Component
final class CanonicalEvidenceViewProjector {

    private static final List<String> ANOMALOUS_HOP_STATUS_TERMS = List.of(
            "error", "fail", "timeout", "timed_out", "rejected",
            "unavailable", "denied", "conflict");

    private final DeterministicLogTraceCompressor traceCompressor;

    CanonicalEvidenceViewProjector(DeterministicLogTraceCompressor traceCompressor) {
        this.traceCompressor = traceCompressor;
    }

    ProjectionFacts project(Diagnosis diagnosis) {
        List<String> capabilityLimits = new ArrayList<>();
        ImpactView impact = impact(diagnosis);
        CallChainAndContrast chain = callChainAndContrast(
                diagnosis, impact.blastRadius(), capabilityLimits);
        return new ProjectionFacts(
                impact,
                chain.callChain(),
                chain.contrast(),
                List.copyOf(capabilityLimits));
    }

    private ImpactView impact(Diagnosis diagnosis) {
        IncidentImpact declared = diagnosis.incident().impact();
        String functionScope = safeText(declared.functionScope(), "待确认");
        if (declared.hasMeasuredFacts()) {
            if (hasUsableImpactEvidence(diagnosis.evidence(), declared)) {
                return new ImpactView(
                        functionScope,
                        declared.affectedCustomers(),
                        declared.affectedUsers(),
                        declared.blastRadius(),
                        declared.evidenceRefs(),
                        declared.observedAt(),
                        safeText(
                                declared.note(),
                                "影响数字和扩散范围来自本次 Diagnosis 保存的证据引用。"));
            }
            return new ImpactView(
                    functionScope,
                    null,
                    null,
                    BlastRadius.UNKNOWN,
                    List.of(),
                    null,
                    "Intake 提供了结构化影响数字或扩散范围，但其引用未命中本次非缺失证据；"
                            + "已按未知处理，未展示未证实数字。");
        }
        EvidenceResult volumeEvidence = firstEvidenceWithFields(
                diagnosis.evidence(), "count", "trace_id");
        Long observedCount = nonNegativeLong(volumeEvidence == null
                ? null
                : volumeEvidence.observed().get("count"));
        if (volumeEvidence != null && observedCount != null) {
            return new ImpactView(
                    functionScope,
                    null,
                    null,
                    BlastRadius.UNKNOWN,
                    List.of(volumeEvidence.queryId()),
                    volumeEvidence.collectedAt(),
                    "观测窗口内记录 " + observedCount
                            + " 条相关日志或事件；这不是客户数或用户数，影响人数仍待核实。"
            );
        }
        return new ImpactView(
                functionScope,
                null,
                null,
                BlastRadius.UNKNOWN,
                List.of(),
                null,
                "当前 intake 只保存文本影响描述，未保存有证据引用的客户数、用户数或扩散范围。"
        );
    }

    private boolean hasUsableImpactEvidence(
            List<EvidenceResult> evidence,
            IncidentImpact impact) {
        if (impact.evidenceRefs().isEmpty()) {
            return false;
        }
        List<EvidenceResult> referenced = evidence.stream()
                .filter(item -> item.status() != EvidenceStatus.MISSING)
                .filter(item -> impact.evidenceRefs().contains(item.queryId()))
                .filter(item -> CanonicalEvidenceSchema.isValid(
                        "incident_impact", item.observed()))
                .toList();
        if (referenced.size() != impact.evidenceRefs().size()) {
            return false;
        }
        if (referenced.stream().anyMatch(item -> !fieldEquals(
                item, "function_scope", impact.functionScope()))) {
            return false;
        }
        if (!allPresentValuesMatch(
                referenced, "affected_customers", impact.affectedCustomers())) {
            return false;
        }
        if (!allPresentValuesMatch(
                referenced, "affected_users", impact.affectedUsers())) {
            return false;
        }
        if (referenced.stream().anyMatch(item -> !fieldEquals(
                item, "blast_radius", impact.blastRadius().name()))) {
            return false;
        }
        return impact.observedAt() == null || referenced.stream().allMatch(item ->
                instantEquals(item.observed().get("observed_at"), impact.observedAt()));
    }

    private boolean allPresentValuesMatch(
            List<EvidenceResult> evidence,
            String field,
            Integer expected) {
        if (expected == null) {
            return true;
        }
        boolean found = false;
        for (EvidenceResult item : evidence) {
            if (!item.observed().containsKey(field)) {
                continue;
            }
            found = true;
            if (!exactInt(item.observed().get(field), expected)) {
                return false;
            }
        }
        return found;
    }

    private boolean fieldEquals(EvidenceResult evidence, String field, String expected) {
        Object value = evidence.observed().get(field);
        return value instanceof String text && text.equals(expected);
    }

    private boolean exactInt(Object raw, int expected) {
        Long value = CanonicalNumberParser.parseExactLong(raw);
        return value != null && value == expected;
    }

    private boolean instantEquals(Object raw, Instant expected) {
        Long epochMillis = CanonicalNumberParser.parseExactLong(raw);
        if (epochMillis == null) {
            return false;
        }
        try {
            return Instant.ofEpochMilli(epochMillis).equals(expected);
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private CallChainAndContrast callChainAndContrast(
            Diagnosis diagnosis,
            BlastRadius blastRadius,
            List<String> capabilityLimits) {
        EvidenceResult traceBundle = firstEvidenceWithFields(
                diagnosis.evidence(), "ps_id", "entries");
        EvidenceResult contrastEvidence = firstEvidenceWithFields(
                diagnosis.evidence(),
                "discriminating_feature",
                "failure_sample_count",
                "failure_match_count",
                "success_sample_count",
                "success_match_count");

        if (traceBundle != null) {
            try {
                LogTraceSkeleton skeleton = traceCompressor.compress(
                        traceBundle, contrastEvidence);
                if (skeleton.omittedEntryCount() > 0) {
                    capabilityLimits.add("调用链按安全预算压缩，省略 "
                            + skeleton.omittedEntryCount() + " 条非关键日志事件。");
                }
                return new CallChainAndContrast(
                        callChain(traceBundle, skeleton, blastRadius),
                        contrast(skeleton.contrast(), contrastEvidence));
            } catch (IllegalArgumentException malformedBundleOrContrast) {
                if (contrastEvidence != null) {
                    try {
                        LogTraceSkeleton skeleton = traceCompressor.compress(traceBundle);
                        capabilityLimits.add(
                                "成功样本对照不符合 canonical contract，统计值已拒绝展示。");
                        return new CallChainAndContrast(
                                callChain(traceBundle, skeleton, blastRadius),
                                unavailableContrast(
                                        "已取得对照证据，但其结构无法安全复算；不能据此判断无异常。"));
                    } catch (IllegalArgumentException malformedTrace) {
                        capabilityLimits.add(
                                "调用链证据不符合 canonical contract，已拒绝投影其内容。");
                    }
                } else {
                    capabilityLimits.add(
                            "调用链证据不符合 canonical contract，已拒绝投影其内容。");
                }
            }
        }

        EvidenceResult failedHopEvidence = firstEvidenceWithFields(
                diagnosis.evidence(), "failed_hop", "status", "duration_ms");
        Hop failedHop = partialHop(failedHopEvidence);
        if (failedHop != null) {
            capabilityLimits.add(
                    "当前 Diagnosis 仅保存异常 hop，未保存完整调用链；该节点不能冒充端到端链路。");
            if (contrastEvidence != null) {
                capabilityLimits.add(
                        "已有成功样本对照证据，但缺少可复算的失败调用链，暂不展示统计结论。");
            }
            return new CallChainAndContrast(
                    new CallChainView(
                            diagnosis.incident().traceId(),
                            List.of(failedHop),
                            null,
                            blastRadius),
                    unavailableContrast(contrastEvidence == null
                            ? "当前 Diagnosis 未保存同窗口成功样本对照，不能把对照缺失解释成无异常。"
                            : "对照证据尚不能与完整失败链路一起复算，不能据此判断无异常。"));
        }

        capabilityLimits.add("当前 Diagnosis 尚未保存可复算的完整调用链 hop。");
        if (contrastEvidence != null) {
            capabilityLimits.add("已有成功样本对照证据，但缺少失败调用链，暂不展示统计结论。");
        } else {
            capabilityLimits.add("当前 Diagnosis 尚未保存同窗口成功样本对照。");
        }
        String reason = diagnosis.incident().traceId() == null
                ? "当前诊断未关联 PS / Trace ID，也未保存可重放的调用链 hop。"
                : "已关联 PS / Trace ID，但当前 Diagnosis 未保存可复算的 hop 列表。";
        return new CallChainAndContrast(
                new CallChainView(
                        diagnosis.incident().traceId(), List.of(), reason, blastRadius),
                unavailableContrast(contrastEvidence == null
                        ? "当前 Diagnosis 未保存同窗口成功样本对照，不能把对照缺失解释成无异常。"
                        : "对照证据尚不能与失败调用链一起复算，不能据此判断无异常。"));
    }

    private CallChainView callChain(
            EvidenceResult traceBundle,
            LogTraceSkeleton skeleton,
            BlastRadius blastRadius) {
        List<Hop> hops = skeleton.timeline().stream()
                .map(event -> new Hop(
                        traceBundle.queryId() + "#" + event.sequenceIndex(),
                        event.service(),
                        duration(event.durationMs()),
                        event.anomalous()))
                .toList();
        return new CallChainView(skeleton.psId(), hops, null, blastRadius);
    }

    private Hop partialHop(EvidenceResult evidence) {
        if (evidence == null) {
            return null;
        }
        Object rawService = evidence.observed().get("failed_hop");
        Object rawStatus = evidence.observed().get("status");
        Object rawDuration = evidence.observed().get("duration_ms");
        Double duration = CanonicalNumberParser.parseFiniteNonNegativeDouble(rawDuration);
        if (!(rawService instanceof String service)
                || service.isBlank()
                || service.length() > 128
                || duration == null
                || !anomalousHopStatus(rawStatus)) {
            return null;
        }
        String sanitizedService = TroubleshootingSecretRedactor.redact(service.trim());
        if (sanitizedService.isBlank() || sanitizedService.length() > 128) {
            return null;
        }
        return new Hop(
                evidence.queryId(),
                sanitizedService,
                duration(duration),
                true);
    }

    private boolean anomalousHopStatus(Object raw) {
        if (!(raw instanceof String status)
                || status.isBlank()
                || status.length() > 64) {
            return false;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        return ANOMALOUS_HOP_STATUS_TERMS.stream().anyMatch(normalized::contains);
    }

    private ContrastView contrast(
            LogTraceSkeleton.ContrastSummary summary,
            EvidenceResult evidence) {
        if (!summary.available() || evidence == null) {
            return unavailableContrast(
                    "当前 Diagnosis 未保存同窗口成功样本对照，不能把对照缺失解释成无异常。");
        }
        return new ContrastView(
                true,
                "失败样本 " + summary.failureMatchCount() + "/" + summary.failureSampleCount()
                        + "（" + percentage(summary.failureRate()) + "）",
                "成功样本 " + summary.successMatchCount() + "/" + summary.successSampleCount()
                        + "（" + percentage(summary.successRate()) + "）",
                "区分特征 " + summary.discriminatingFeature()
                        + "，失败与成功样本相差 " + percentagePoints(summary.rateDelta())
                        + " 个百分点。",
                List.of(evidence.queryId()));
    }

    private ContrastView unavailableContrast(String note) {
        return new ContrastView(false, null, null, note, List.of());
    }

    private EvidenceResult firstEvidenceWithFields(
            List<EvidenceResult> evidence,
            String... requiredFields) {
        // EvidenceResult predates the signalKind field. Canonical observed shapes are
        // disjoint, so projection can reuse stored evidence without a schema/table change.
        for (EvidenceResult item : evidence) {
            if (item.status() == EvidenceStatus.MISSING) {
                continue;
            }
            Map<String, Object> observed = item.observed();
            boolean matches = true;
            for (String field : requiredFields) {
                if (!observed.containsKey(field)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return item;
            }
        }
        return null;
    }

    private String safeText(String value, String fallback) {
        String sanitized = TroubleshootingSecretRedactor.redact(
                value == null ? "" : value.trim());
        return sanitized.isBlank() ? fallback : sanitized;
    }

    private String duration(Double durationMs) {
        if (durationMs == null) {
            return "未记录";
        }
        return BigDecimal.valueOf(durationMs).stripTrailingZeros().toPlainString() + " ms";
    }

    private String percentage(double rate) {
        return BigDecimal.valueOf(rate * 100d).stripTrailingZeros().toPlainString() + "%";
    }

    private String percentagePoints(double delta) {
        return BigDecimal.valueOf(delta * 100d).stripTrailingZeros().toPlainString();
    }

    private Long nonNegativeLong(Object raw) {
        Long value = CanonicalNumberParser.parseExactLong(raw);
        return value == null || value < 0 ? null : value;
    }

    record ProjectionFacts(
            ImpactView impact,
            CallChainView callChain,
            ContrastView contrast,
            List<String> capabilityLimits) {
    }

    private record CallChainAndContrast(
            CallChainView callChain,
            ContrastView contrast) {
    }
}
