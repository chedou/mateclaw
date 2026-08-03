package vip.mate.troubleshooting.projection;

import com.fasterxml.jackson.annotation.JsonFormat;
import vip.mate.troubleshooting.model.EvidenceStatus;

import java.lang.reflect.Array;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable, read-only explanation of the seven observable investigation stages.
 *
 * <p>This is an experience projection, not a second runtime ledger. It only
 * describes facts already frozen in a Diagnosis, its exact Playbook version,
 * canonical evidence and deterministic derivation. A nullable timestamp or
 * duration means the runtime never persisted that fact; callers must render it
 * as {@value #UNRECORDED} instead of estimating it.</p>
 */
public record InvestigationTraceView(
        String diagnosisId,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Duration investigationDuration,
        List<StageView> stages,
        List<EvidenceContractView> evidenceContracts,
        List<AdapterAttemptView> adapterAttempts,
        StopReasonView stopReason,
        EvidenceRelationView evidenceRelation) {

    public static final String UNRECORDED = "未记录";

    public InvestigationTraceView {
        diagnosisId = required(diagnosisId, "diagnosisId");
        stages = List.copyOf(stages == null ? List.of() : stages);
        evidenceContracts = List.copyOf(
                evidenceContracts == null ? List.of() : evidenceContracts);
        adapterAttempts = List.copyOf(
                adapterAttempts == null ? List.of() : adapterAttempts);
        if (stages.size() != StageKey.values().length) {
            throw new IllegalArgumentException("the investigation trace must contain seven stages");
        }
        for (int index = 0; index < StageKey.values().length; index++) {
            StageView stage = stages.get(index);
            if (stage.sequence() != index + 1 || stage.key() != StageKey.values()[index]) {
                throw new IllegalArgumentException(
                        "investigation stages must use the canonical sequence");
            }
        }
        if (stopReason == null || evidenceRelation == null) {
            throw new IllegalArgumentException("stopReason and evidenceRelation are required");
        }
        requireNonNegative(investigationDuration, "investigationDuration");
    }

    /** Source-compatible fallback for callers that have no aggregate to project. */
    public static InvestigationTraceView unrecorded(String diagnosisId) {
        String[] titles = {
            "排障事件",
            "调查路径 / Playbook",
            "证据合同",
            "选择适配器",
            "获取只读证据",
            "判据计算",
            "结论或弃权"
        };
        List<StageView> stages = new ArrayList<>();
        for (int index = 0; index < StageKey.values().length; index++) {
            stages.add(new StageView(
                    index + 1,
                    StageKey.values()[index],
                    titles[index],
                    StageStatus.UNRECORDED,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of()));
        }
        return new InvestigationTraceView(
                diagnosisId,
                null,
                stages,
                List.of(),
                List.of(),
                new StopReasonView(StopReasonCode.UNRECORDED, null, null, List.of()),
                new EvidenceRelationView(
                        false,
                        List.of(new RelationNode(
                                "conclusion:" + diagnosisId,
                                RelationNodeKind.CONCLUSION,
                                UNRECORDED,
                                null,
                                UNRECORDED,
                                diagnosisId)),
                        List.of(),
                        null));
    }

    public enum StageKey {
        INCIDENT,
        PLAYBOOK_ROUTE,
        EVIDENCE_CONTRACT,
        ADAPTER_SELECTION,
        EVIDENCE_COLLECTION,
        CRITERION_EVALUATION,
        CONCLUSION
    }

    public enum StageStatus {
        COMPLETED,
        PARTIAL,
        STOPPED,
        UNRECORDED
    }

    public enum AttemptHistoryStatus {
        /** The final canonical result is known; candidate and retry attempts were not persisted. */
        FINAL_RESULT_ONLY
    }

    public enum StopReasonCode {
        CONCLUSION_RECORDED,
        EVIDENCE_MISSING,
        SOURCE_UNAVAILABLE,
        ABSTAINED,
        UNRECORDED
    }

    public enum RelationNodeKind {
        EVIDENCE,
        CRITERION,
        RULE,
        CONCLUSION
    }

    public enum RelationType {
        SUPPORTS,
        REFUTES,
        BLOCKS,
        CITES
    }

    public record StageView(
            int sequence,
            StageKey key,
            String title,
            StageStatus status,
            String summary,
            Instant startedAt,
            Instant completedAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            Duration duration,
            List<TraceField> fields,
            List<String> evidenceRefs) {

        public StageView {
            if (sequence < 1 || key == null || status == null) {
                throw new IllegalArgumentException("stage sequence, key and status are required");
            }
            title = required(title, "title");
            summary = display(summary);
            fields = List.copyOf(fields == null ? List.of() : fields);
            evidenceRefs = List.copyOf(evidenceRefs == null ? List.of() : evidenceRefs);
            requireNonNegative(duration, "stage duration");
            if (startedAt != null && completedAt != null && completedAt.isBefore(startedAt)) {
                throw new IllegalArgumentException("stage completion cannot precede its start");
            }
        }
    }

    public record TraceField(String label, String value) {
        public TraceField {
            label = required(label, "label");
            value = display(value);
        }
    }

    public record EvidenceContractView(
            String requestId,
            String signalKind,
            String purpose,
            Map<String, Object> target,
            String window,
            boolean required) {

        public EvidenceContractView {
            requestId = InvestigationTraceView.required(requestId, "requestId");
            signalKind = InvestigationTraceView.required(signalKind, "signalKind");
            purpose = display(purpose);
            target = immutableMap(target);
            window = display(window);
        }
    }

    /**
     * One persisted final adapter result. The runtime currently does not retain
     * candidate order, retries or per-attempt latency, so duration stays null and
     * historyStatus makes that limitation explicit.
     */
    public record AdapterAttemptView(
            String evidenceRef,
            String requestId,
            String signalKind,
            String adapterSource,
            EvidenceStatus status,
            String summary,
            String query,
            Map<String, Object> observed,
            Instant collectedAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
            Duration duration,
            AttemptHistoryStatus historyStatus) {

        public AdapterAttemptView {
            evidenceRef = required(evidenceRef, "evidenceRef");
            requestId = display(requestId);
            signalKind = display(signalKind);
            adapterSource = display(adapterSource);
            if (status == null || historyStatus == null) {
                throw new IllegalArgumentException("adapter status and historyStatus are required");
            }
            summary = display(summary);
            query = display(query);
            observed = immutableMap(observed);
            requireNonNegative(duration, "adapter duration");
        }
    }

    public record StopReasonView(
            StopReasonCode code,
            String message,
            Instant stoppedAt,
            List<String> evidenceRefs) {

        public StopReasonView {
            if (code == null) {
                throw new IllegalArgumentException("stop reason code is required");
            }
            message = display(message);
            evidenceRefs = List.copyOf(evidenceRefs == null ? List.of() : evidenceRefs);
        }
    }

    public record EvidenceRelationView(
            boolean available,
            List<RelationNode> nodes,
            List<RelationEdge> edges,
            String emptyReason) {

        public EvidenceRelationView {
            nodes = List.copyOf(nodes == null ? List.of() : nodes);
            edges = List.copyOf(edges == null ? List.of() : edges);
            emptyReason = available ? normalizeNullable(emptyReason) : display(emptyReason);
            if (available && edges.isEmpty()) {
                throw new IllegalArgumentException("an available evidence relation requires edges");
            }
        }
    }

    public record RelationNode(
            String nodeId,
            RelationNodeKind kind,
            String label,
            String detail,
            String status,
            String ref) {

        public RelationNode {
            nodeId = required(nodeId, "nodeId");
            if (kind == null) {
                throw new IllegalArgumentException("relation node kind is required");
            }
            label = required(label, "label");
            detail = display(detail);
            status = display(status);
            ref = display(ref);
        }
    }

    public record RelationEdge(
            String edgeId,
            String fromNodeId,
            String toNodeId,
            RelationType relation,
            String label) {

        public RelationEdge {
            edgeId = required(edgeId, "edgeId");
            fromNodeId = required(fromNodeId, "fromNodeId");
            toNodeId = required(toNodeId, "toNodeId");
            if (relation == null) {
                throw new IllegalArgumentException("relation is required");
            }
            label = required(label, "label");
        }
    }

    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(required(key, "map key"), immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value == null) {
            return UNRECORDED;
        }
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> copy = new LinkedHashMap<>();
            nested.forEach((key, item) -> copy.put(display(String.valueOf(key)), immutableValue(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> copy = new ArrayList<>();
            iterable.forEach(item -> copy.add(immutableValue(item)));
            return List.copyOf(copy);
        }
        if (value.getClass().isArray()) {
            List<Object> copy = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                copy.add(immutableValue(Array.get(value, index)));
            }
            return List.copyOf(copy);
        }
        return value;
    }

    private static void requireNonNegative(Duration duration, String name) {
        if (duration != null && duration.isNegative()) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
    }

    static String display(String value) {
        return value == null || value.isBlank() ? UNRECORDED : value.trim();
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
