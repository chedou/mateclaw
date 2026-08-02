package vip.mate.troubleshooting.projection;

import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.BlastRadius;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.RouteAuthority;

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
            String narrative,
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
            if (conclusionType == null || confidence == null || impact == null
                    || nextStep == null || status == null || timings == null) {
                throw new IllegalArgumentException(
                        "conclusionType, confidence, impact, nextStep, status and timings are required");
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
            String playbookRef,
            KnowledgeEvidenceGrade knowledgeEvidenceGrade,
            List<ScenarioAffordance> scenarioAffordances,
            CallChainView callChain,
            List<EvidenceStep> steps,
            ContrastView contrast,
            DraftView draft,
            List<String> capabilityLimits,
            boolean fixtureMode) {

        public DeveloperEvidenceView {
            diagnosisId = required(diagnosisId, "diagnosisId");
            playbookRef = normalizeNullable(playbookRef);
            if (playbookRef == null && knowledgeEvidenceGrade != null) {
                throw new IllegalArgumentException(
                        "knowledge evidence grade requires a Playbook reference");
            }
            if (playbookRef != null && knowledgeEvidenceGrade == null) {
                knowledgeEvidenceGrade = KnowledgeEvidenceGrade.UNVERIFIED;
            }
            if (investigationMode == null || routeAuthority == null || callChain == null
                    || contrast == null || draft == null) {
                throw new IllegalArgumentException(
                        "investigationMode, routeAuthority, callChain, contrast and draft are required");
            }
            steps = List.copyOf(steps == null ? List.of() : steps);
            scenarioAffordances = List.copyOf(
                    scenarioAffordances == null ? List.of() : scenarioAffordances);
            capabilityLimits = List.copyOf(
                    capabilityLimits == null ? List.of() : capabilityLimits);
            if (scenarioAffordances.stream().map(ScenarioAffordance::scenarioKey).distinct()
                    .count() != scenarioAffordances.size()) {
                throw new IllegalArgumentException("scenarioAffordances must have unique scenarioKey");
            }
        }

        /** Compatibility shape for projections created before T0.9. */
        public DeveloperEvidenceView(
                String diagnosisId,
                InvestigationMode investigationMode,
                RouteAuthority routeAuthority,
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
                    playbookRef,
                    playbookRef == null ? null : KnowledgeEvidenceGrade.UNVERIFIED,
                    scenarioAffordances,
                    callChain,
                    steps,
                    contrast,
                    draft,
                    capabilityLimits,
                    fixtureMode);
        }

        /** True when the named scenario is offered on this diagnosis and still required. */
        public boolean requiresScenario(String scenarioKey) {
            return scenarioAffordances.stream()
                    .anyMatch(item -> item.scenarioKey().equals(scenarioKey) && item.required());
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

    public record ContrastView(
            boolean available,
            String failedSample,
            String baselineSample,
            String note,
            List<String> evidenceRefs) {

        public ContrastView {
            failedSample = normalizeNullable(failedSample);
            baselineSample = normalizeNullable(baselineSample);
            note = note == null ? "" : note.trim();
            evidenceRefs = List.copyOf(evidenceRefs == null ? List.of() : evidenceRefs);
            if (available && (blank(failedSample) || blank(baselineSample) || evidenceRefs.isEmpty())) {
                throw new IllegalArgumentException(
                        "available contrast requires both samples and evidenceRefs");
            }
            if (!available && blank(note)) {
                throw new IllegalArgumentException("unavailable contrast requires a note");
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
