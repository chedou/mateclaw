package vip.mate.troubleshooting.projection;

import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.BlastRadius;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.RouteAuthority;
import vip.mate.troubleshooting.model.RouteSemanticsProvenance;

import java.time.Instant;
import java.util.List;

/**
 * The two audience projections rendered by the formal troubleshooting workbench.
 *
 * <p>Both views are created from one stored diagnosis. The browser receives
 * typed conclusions, impact and evidence semantics and therefore never needs
 * to infer a business conclusion from low-level diagnostic fields.</p>
 */
public record DiagnosisExperienceProjection(
        BusinessSummary businessSummary,
        DeveloperEvidenceView developerEvidence) {

    public DiagnosisExperienceProjection {
        if (businessSummary == null || developerEvidence == null) {
            throw new IllegalArgumentException("businessSummary and developerEvidence are required");
        }
        if (!businessSummary.diagnosisId().equals(developerEvidence.diagnosisId())) {
            throw new IllegalArgumentException("both projections must describe the same diagnosis");
        }
    }

    public enum StepTone {
        NORMAL,
        ANOMALY,
        EXCLUDED,
        UNEVALUATED
    }

    public enum EvidenceStepKind {
        EVIDENCE,
        CRITERION
    }

    public enum ReviewStatus {
        DRAFT,
        CANDIDATE
    }

    public record BusinessSummary(
            String diagnosisId,
            ConclusionType conclusionType,
            String headline,
            // The one thing the reader came for. It is a field rather than a
            // sentence inside narrative because a reader who stops after two
            // lines should still have the answer.
            String rootCause,
            String narrative,
            // The counts that make the conclusion checkable rather than
            // something the reader has to take on faith. Plain language, no
            // query text: aggregate counts are business facts, DQL is not.
            String keyEvidence,
            Confidence confidence,
            String problem,
            ImpactView impact,
            NextStep nextStep,
            DiagnosisStatus status,
            NorthStarTimings timings,
            boolean fixtureMode) {

        public BusinessSummary {
            diagnosisId = required(diagnosisId, "diagnosisId");
            headline = required(headline, "headline");
            narrative = required(narrative, "narrative");
            problem = required(problem, "problem");
            rootCause = normalizeNullable(rootCause);
            keyEvidence = normalizeNullable(keyEvidence);
            if (conclusionType == null || confidence == null || impact == null
                    || nextStep == null || status == null || timings == null) {
                throw new IllegalArgumentException(
                        "conclusionType, confidence, impact, nextStep, status and timings are required");
            }
            // An abstention that still names a cause is the failure mode the
            // whole abstain path exists to prevent.
            if (conclusionType == ConclusionType.INSUFFICIENT_EVIDENCE && rootCause != null) {
                throw new IllegalArgumentException(
                        "INSUFFICIENT_EVIDENCE conclusions must not name a root cause");
            }
            if (conclusionType == ConclusionType.EXCLUDED && confidence == Confidence.HIGH) {
                throw new IllegalArgumentException("EXCLUDED conclusions cannot have HIGH confidence");
            }
            if (conclusionType == ConclusionType.INSUFFICIENT_EVIDENCE
                    && confidence != Confidence.LOW) {
                throw new IllegalArgumentException(
                        "INSUFFICIENT_EVIDENCE conclusions must have LOW confidence");
            }
            if ((conclusionType == ConclusionType.EXCLUDED
                    || conclusionType == ConclusionType.HYPOTHESIS
                    || conclusionType == ConclusionType.INSUFFICIENT_EVIDENCE)
                    && blank(nextStep.capabilityBoundary())) {
                throw new IllegalArgumentException(
                        conclusionType + " requires an explicit capability boundary");
            }
        }
    }

    public record ImpactView(
            String functionScope,
            Integer affectedCustomers,
            Integer affectedUsers,
            BlastRadius blastRadius,
            List<String> evidenceRefs,
            Instant observedAt,
            String note) {

        public ImpactView {
            functionScope = required(functionScope, "functionScope");
            if (affectedCustomers != null && affectedCustomers < 0) {
                throw new IllegalArgumentException("affectedCustomers cannot be negative");
            }
            if (affectedUsers != null && affectedUsers < 0) {
                throw new IllegalArgumentException("affectedUsers cannot be negative");
            }
            blastRadius = blastRadius == null ? BlastRadius.UNKNOWN : blastRadius;
            evidenceRefs = List.copyOf(evidenceRefs == null ? List.of() : evidenceRefs);
            note = note == null ? "" : note.trim();
            if ((affectedCustomers != null || affectedUsers != null) && evidenceRefs.isEmpty()) {
                throw new IllegalArgumentException(
                        "precise impact counts require non-empty evidenceRefs");
            }
        }
    }

    public record NextStep(String label, String text, String capabilityBoundary) {
        public NextStep {
            label = required(label, "label");
            text = required(text, "text");
            capabilityBoundary = normalizeNullable(capabilityBoundary);
        }
    }

    public record DeveloperEvidenceView(
            String diagnosisId,
            InvestigationMode investigationMode,
            RouteAuthority routeAuthority,
            RouteSemanticsProvenance routeSemanticsProvenance,
            String playbookRef,
            KnowledgeEvidenceGrade knowledgeEvidenceGrade,
            List<ScenarioAffordance> scenarioAffordances,
            CallChainView callChain,
            List<EvidenceStep> steps,
            InvestigationTraceView investigationTrace,
            ContrastView contrast,
            DraftView draft,
            List<String> capabilityLimits,
            boolean fixtureMode,
            FailureBreakdownView failureBreakdown) {

        public DeveloperEvidenceView {
            diagnosisId = required(diagnosisId, "diagnosisId");
            playbookRef = normalizeNullable(playbookRef);
            if (routeSemanticsProvenance == null) {
                throw new IllegalArgumentException("routeSemanticsProvenance is required");
            }
            if (playbookRef == null && knowledgeEvidenceGrade != null) {
                throw new IllegalArgumentException(
                        "knowledge evidence grade requires a Playbook reference");
            }
            if (playbookRef != null && knowledgeEvidenceGrade == null) {
                knowledgeEvidenceGrade = KnowledgeEvidenceGrade.UNVERIFIED;
            }
            if (investigationMode == null || routeAuthority == null || callChain == null
                    || investigationTrace == null
                    || contrast == null || draft == null) {
                throw new IllegalArgumentException(
                        "investigationMode, routeAuthority, callChain, investigationTrace, "
                                + "contrast and draft are required");
            }
            if (!diagnosisId.equals(investigationTrace.diagnosisId())) {
                throw new IllegalArgumentException(
                        "investigationTrace must describe the same diagnosis");
            }
            steps = List.copyOf(steps == null ? List.of() : steps);
            scenarioAffordances = List.copyOf(
                    scenarioAffordances == null ? List.of() : scenarioAffordances);
            capabilityLimits = List.copyOf(
                    capabilityLimits == null ? List.of() : capabilityLimits);
            failureBreakdown = failureBreakdown == null
                    ? FailureBreakdownView.unavailable()
                    : failureBreakdown;
            if (scenarioAffordances.stream().map(ScenarioAffordance::scenarioKey).distinct()
                    .count() != scenarioAffordances.size()) {
                throw new IllegalArgumentException("scenarioAffordances must have unique scenarioKey");
            }
        }

        /** Compatibility shape for projections created before failure grouping. */
        public DeveloperEvidenceView(
                String diagnosisId,
                InvestigationMode investigationMode,
                RouteAuthority routeAuthority,
                RouteSemanticsProvenance routeSemanticsProvenance,
                String playbookRef,
                KnowledgeEvidenceGrade knowledgeEvidenceGrade,
                List<ScenarioAffordance> scenarioAffordances,
                CallChainView callChain,
                List<EvidenceStep> steps,
                InvestigationTraceView investigationTrace,
                ContrastView contrast,
                DraftView draft,
                List<String> capabilityLimits,
                boolean fixtureMode) {
            this(
                    diagnosisId, investigationMode, routeAuthority,
                    routeSemanticsProvenance, playbookRef, knowledgeEvidenceGrade,
                    scenarioAffordances, callChain, steps, investigationTrace,
                    contrast, draft, capabilityLimits, fixtureMode, null);
        }

        /** Compatibility shape for projections created before the seven-stage trace. */
        public DeveloperEvidenceView(
                String diagnosisId,
                InvestigationMode investigationMode,
                RouteAuthority routeAuthority,
                RouteSemanticsProvenance routeSemanticsProvenance,
                String playbookRef,
                KnowledgeEvidenceGrade knowledgeEvidenceGrade,
                List<ScenarioAffordance> scenarioAffordances,
                CallChainView callChain,
                List<EvidenceStep> steps,
                ContrastView contrast,
                DraftView draft,
                List<String> capabilityLimits,
                boolean fixtureMode) {
            this(
                    diagnosisId,
                    investigationMode,
                    routeAuthority,
                    routeSemanticsProvenance,
                    playbookRef,
                    knowledgeEvidenceGrade,
                    scenarioAffordances,
                    callChain,
                    steps,
                    InvestigationTraceView.unrecorded(diagnosisId),
                    contrast,
                    draft,
                    capabilityLimits,
                    fixtureMode,
                    null);
        }

        /** Compatibility shape for projections created before T0.9. */
        public DeveloperEvidenceView(
                String diagnosisId,
                InvestigationMode investigationMode,
                RouteAuthority routeAuthority,
                RouteSemanticsProvenance routeSemanticsProvenance,
                String playbookRef,
                List<ScenarioAffordance> scenarioAffordances,
                CallChainView callChain,
                List<EvidenceStep> steps,
                ContrastView contrast,
                DraftView draft,
                List<String> capabilityLimits,
                boolean fixtureMode) {
            this(
                    diagnosisId,
                    investigationMode,
                    routeAuthority,
                    routeSemanticsProvenance,
                    playbookRef,
                    playbookRef == null ? null : KnowledgeEvidenceGrade.UNVERIFIED,
                    scenarioAffordances,
                    callChain,
                    steps,
                    InvestigationTraceView.unrecorded(diagnosisId),
                    contrast,
                    draft,
                    capabilityLimits,
                    fixtureMode,
                    null);
        }

        /** True when the named scenario is offered on this diagnosis and still required. */
        public boolean requiresScenario(String scenarioKey) {
            return scenarioAffordances.stream()
                    .anyMatch(item -> item.scenarioKey().equals(scenarioKey) && item.required());
        }
    }

    public record FailureBreakdownView(
            boolean available,
            long totalRequests,
            long classifiedRequests,
            long unclassifiedRequests,
            List<FailureGroupView> groups,
            String note,
            List<String> evidenceRefs) {

        public FailureBreakdownView {
            groups = List.copyOf(groups == null ? List.of() : groups);
            note = required(note, "note");
            evidenceRefs = List.copyOf(evidenceRefs == null ? List.of() : evidenceRefs);
            long projectedTotal;
            long groupedTotal;
            try {
                projectedTotal = Math.addExact(classifiedRequests, unclassifiedRequests);
                groupedTotal = groups.stream()
                        .mapToLong(FailureGroupView::requestCount)
                        .reduce(0L, Math::addExact);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException(
                        "failure breakdown counts are inconsistent", overflow);
            }
            long largestGroup = groups.stream()
                    .mapToLong(FailureGroupView::requestCount)
                    .max()
                    .orElse(0L);
            if (totalRequests < 0 || classifiedRequests < 0 || unclassifiedRequests < 0
                    || projectedTotal != totalRequests || largestGroup > classifiedRequests
                    || classifiedRequests > groupedTotal) {
                throw new IllegalArgumentException("failure breakdown counts are inconsistent");
            }
            if (available && (totalRequests == 0 || evidenceRefs.isEmpty())) {
                throw new IllegalArgumentException(
                        "an available failure breakdown requires requests and evidence refs");
            }
            if (!available && (totalRequests != 0 || !groups.isEmpty()
                    || !evidenceRefs.isEmpty())) {
                throw new IllegalArgumentException(
                        "an unavailable failure breakdown must not invent measurements");
            }
        }

        public static FailureBreakdownView unavailable() {
            return new FailureBreakdownView(
                    false, 0, 0, 0, List.of(),
                    "本次排障没有记录请求级失败分类。", List.of());
        }
    }

    public record FailureGroupView(String code, String label, long requestCount) {
        public FailureGroupView {
            code = required(code, "code");
            label = required(label, "label");
            if (requestCount <= 0) {
                throw new IllegalArgumentException("requestCount must be positive");
            }
        }
    }

    /**
     * One scenario-specific affordance the developer view may offer.
     *
     * <p>Scenario capabilities are carried as a keyed list rather than as booleans
     * on {@link DeveloperEvidenceView}. A boolean per scenario would make every
     * diagnosis carry a flag about a scenario it has nothing to do with, and the
     * record would grow one field per scenario shipped. The projection stays
     * scenario-agnostic; only the key is scenario-specific.</p>
     */
    public record ScenarioAffordance(String scenarioKey, boolean required) {
        public ScenarioAffordance {
            // The accessor `required()` shadows the outer static helper of the same
            // name inside this constructor, so the helper must be qualified.
            scenarioKey = DiagnosisExperienceProjection.required(scenarioKey, "scenarioKey");
        }
    }

    public record CallChainView(
            String psId,
            List<Hop> hops,
            String emptyReason,
            BlastRadius blastRadius) {

        public CallChainView {
            psId = normalizeNullable(psId);
            hops = List.copyOf(hops == null ? List.of() : hops);
            emptyReason = normalizeNullable(emptyReason);
            blastRadius = blastRadius == null ? BlastRadius.UNKNOWN : blastRadius;
            if (hops.isEmpty() && blank(emptyReason)) {
                throw new IllegalArgumentException("an empty call chain requires emptyReason");
            }
        }
    }

    public record Hop(String hopId, String service, String duration, boolean anomalous) {
        public Hop {
            hopId = required(hopId, "hopId");
            service = required(service, "service");
            duration = required(duration, "duration");
        }
    }

    public record EvidenceStep(
            EvidenceStepKind kind,
            Instant at,
            String title,
            String detail,
            String ref,
            StepTone tone) {

        public EvidenceStep {
            if (kind == null) {
                throw new IllegalArgumentException("kind is required");
            }
            if (kind == EvidenceStepKind.EVIDENCE && at == null) {
                throw new IllegalArgumentException("evidence steps require at");
            }
            if (kind == EvidenceStepKind.CRITERION && at != null) {
                throw new IllegalArgumentException("criterion steps do not carry a synthetic timestamp");
            }
            title = required(title, "title");
            detail = detail == null ? "" : detail.trim();
            ref = required(ref, "ref");
            if (tone == null) {
                throw new IllegalArgumentException("tone is required");
            }
        }
    }

    public record ComparisonGroupView(
            long totalRequests,
            long requestsWithFeature) {

        public ComparisonGroupView {
            if (totalRequests <= 0) {
                throw new IllegalArgumentException("totalRequests must be positive");
            }
            if (requestsWithFeature < 0 || requestsWithFeature > totalRequests) {
                throw new IllegalArgumentException(
                        "requestsWithFeature must be between zero and totalRequests");
            }
        }
    }

    public record ContrastView(
            boolean available,
            String featureCode,
            ComparisonGroupView failedRequests,
            ComparisonGroupView normalRequests,
            String note,
            List<String> evidenceRefs) {

        public ContrastView {
            featureCode = normalizeNullable(featureCode);
            note = note == null ? "" : note.trim();
            evidenceRefs = List.copyOf(evidenceRefs == null ? List.of() : evidenceRefs);
            if (available && (blank(featureCode)
                    || failedRequests == null
                    || normalRequests == null
                    || evidenceRefs.isEmpty())) {
                throw new IllegalArgumentException(
                        "available contrast requires a feature, both request groups and evidenceRefs");
            }
            if (!available && blank(note)) {
                throw new IllegalArgumentException("unavailable contrast requires a note");
            }
            if (!available && (!blank(featureCode)
                    || failedRequests != null
                    || normalRequests != null)) {
                throw new IllegalArgumentException(
                        "unavailable contrast must not carry comparison facts");
            }
        }
    }

    public record DraftView(
            String draftId,
            String title,
            List<String> steps,
            String emptyReason,
            ReviewStatus reviewStatus,
            String stateNote) {

        public DraftView {
            draftId = normalizeNullable(draftId);
            title = title == null ? "" : title.trim();
            steps = List.copyOf(steps == null ? List.of() : steps);
            emptyReason = normalizeNullable(emptyReason);
            stateNote = required(stateNote, "stateNote");
            if (reviewStatus == null) {
                throw new IllegalArgumentException("reviewStatus is required");
            }
            if (!steps.isEmpty() && (blank(draftId) || blank(title))) {
                throw new IllegalArgumentException("a populated draft requires draftId and title");
            }
            if (steps.isEmpty() && blank(emptyReason)) {
                throw new IllegalArgumentException("an empty draft requires emptyReason");
            }
        }
    }

    private static String required(String value, String name) {
        if (blank(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        return blank(value) ? null : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

}
