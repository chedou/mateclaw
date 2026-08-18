package vip.mate.troubleshooting.evaluation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.EvidenceSpinePlan;
import vip.mate.troubleshooting.evidence.EvidenceSpinePlanResolver;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptance;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptanceService;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpineObservation;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreviewService;
import vip.mate.troubleshooting.model.ClosureRecord;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.pilot.TroubleshootingPilotPlanService;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.service.TroubleshootingPlaybookVersionService;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;
import vip.mate.troubleshooting.synthesis.ReferenceSolution;
import vip.mate.troubleshooting.synthesis.SopSynthesisPreview;
import vip.mate.troubleshooting.synthesis.SopSynthesisRequest;
import vip.mate.troubleshooting.synthesis.SopSynthesisService;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Captures and finalizes the human-owned T8 historical sample ledger. */
@Service
public class EvidenceEvaluationSampleService {

    private static final Pattern STRUCTURED_KEY =
            Pattern.compile("[a-z][a-z0-9_:-]{1,63}");
    private static final int MAX_INTENTS = 20;
    private static final int MAX_REVISION_ATTEMPTS = 8;

    private final GuanceEvidenceSpinePreviewService previewService;
    private final TroubleshootingPersistenceService persistenceService;
    private final EvidenceEvaluationSampleStore store;
    private final EvaluationModelInputFactory modelInputFactory;
    private final SopSynthesisService replayService;
    private final RecordedReplayEvaluationCapabilityService replayCapabilityService;
    private final GuanceEvidenceAcceptanceService acceptanceService;
    private final TroubleshootingPlaybookVersionService playbookVersions;
    private final TroubleshootingPilotPlanService pilotPlans;
    private final Clock clock;

    @Autowired
    public EvidenceEvaluationSampleService(
            GuanceEvidenceSpinePreviewService previewService,
            TroubleshootingPersistenceService persistenceService,
            EvidenceEvaluationSampleStore store,
            EvaluationModelInputFactory modelInputFactory,
            SopSynthesisService replayService,
            RecordedReplayEvaluationCapabilityService replayCapabilityService,
            GuanceEvidenceAcceptanceService acceptanceService,
            TroubleshootingPlaybookVersionService playbookVersions,
            TroubleshootingPilotPlanService pilotPlans) {
        this(
                previewService,
                persistenceService,
                store,
                modelInputFactory,
                replayService,
                replayCapabilityService,
                acceptanceService,
                playbookVersions,
                pilotPlans,
                Clock.systemUTC());
    }

    EvidenceEvaluationSampleService(
            GuanceEvidenceSpinePreviewService previewService,
            TroubleshootingPersistenceService persistenceService,
            EvidenceEvaluationSampleStore store,
            Clock clock) {
        this(
                previewService,
                persistenceService,
                store,
                new EvaluationModelInputFactory(
                        new ObjectMapper().findAndRegisterModules()),
                null,
                null,
                null,
                null,
                null,
                clock);
    }

    EvidenceEvaluationSampleService(
            GuanceEvidenceSpinePreviewService previewService,
            TroubleshootingPersistenceService persistenceService,
            EvidenceEvaluationSampleStore store,
            EvaluationModelInputFactory modelInputFactory,
            Clock clock) {
        this(
                previewService,
                persistenceService,
                store,
                modelInputFactory,
                null,
                null,
                null,
                null,
                null,
                clock);
    }

    EvidenceEvaluationSampleService(
            GuanceEvidenceSpinePreviewService previewService,
            TroubleshootingPersistenceService persistenceService,
            EvidenceEvaluationSampleStore store,
            EvaluationModelInputFactory modelInputFactory,
            SopSynthesisService replayService,
            Clock clock) {
        this(
                previewService,
                persistenceService,
                store,
                modelInputFactory,
                replayService,
                null,
                null,
                null,
                null,
                clock);
    }

    EvidenceEvaluationSampleService(
            GuanceEvidenceSpinePreviewService previewService,
            TroubleshootingPersistenceService persistenceService,
            EvidenceEvaluationSampleStore store,
            EvaluationModelInputFactory modelInputFactory,
            SopSynthesisService replayService,
            RecordedReplayEvaluationCapabilityService replayCapabilityService,
            GuanceEvidenceAcceptanceService acceptanceService,
            TroubleshootingPlaybookVersionService playbookVersions,
            TroubleshootingPilotPlanService pilotPlans,
            Clock clock) {
        this.previewService = previewService;
        this.persistenceService = persistenceService;
        this.store = store;
        this.modelInputFactory = modelInputFactory;
        this.replayService = replayService;
        this.replayCapabilityService = replayCapabilityService;
        this.acceptanceService = acceptanceService;
        this.playbookVersions = playbookVersions;
        this.pilotPlans = pilotPlans;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Re-runs the Guance-only Evidence Spine and persists the resulting safe projection.
     * Browser-supplied preview data is never accepted as evidence.
     */
    public EvidenceEvaluationSampleStore.StoredSample capture(
            long workspaceId,
            String diagnosisId,
            String actor) {
        validateWorkspace(workspaceId);
        String normalizedDiagnosisId = required(diagnosisId, "diagnosisId");
        String normalizedActor = required(actor, "actor");

        StoredDiagnosis storedDiagnosis =
                persistenceService.get(workspaceId, normalizedDiagnosisId);
        Diagnosis diagnosis = storedDiagnosis.diagnosis();
        IncidentContext incident = diagnosis.incident();
        FrozenCaptureTarget target = frozenCaptureTarget(workspaceId, storedDiagnosis);
        String normalizedScenario = target.scenarioKey();
        EvidenceSpinePlan plan = target.plan();
        if (acceptanceService == null) {
            throw conflict("T7 owner acceptance is not configured");
        }
        requireCurrentPilot(workspaceId, target, incident);
        GuanceEvidenceAcceptance acceptedBefore = acceptanceService.requireAccepted(
                workspaceId, incident.system(), incident.service());
        Instant occurredAt = incident.occurredAt() == null
                ? Instant.now(clock)
                : incident.occurredAt();
        String captureIdentityKey = EvaluationKeys.captureIdentityKey(
                workspaceId,
                normalizedDiagnosisId,
                normalizedScenario,
                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                plan.searchTerm(),
                plan.window(),
                occurredAt);
        GuanceEvidenceSpineObservation observation = previewService.observe(
                workspaceId,
                incident.system(),
                incident.service(),
                plan.searchTerm(),
                plan.window(),
                occurredAt);
        revalidateFormalAuthority(
                workspaceId, incident, target, acceptedBefore);
        GuanceEvidenceSpinePreview preview = observation.preview();
        if (preview.stage() != GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED) {
            throw conflict(
                    "the full Guance Evidence Spine was not observed; "
                            + "no formal sample was persisted");
        }
        EvaluationModelInputFactory.FingerprintedInput modelInput = modelInputFactory.create(
                incident.system(),
                incident.service(),
                normalizedScenario,
                observation);
        try {
            return persistRevision(
                    workspaceId,
                    captureIdentityKey,
                    modelInput.fingerprint(),
                    new FormalSampleIdentity(
                            target.pilotPlanVersion(), target.playbookVersionRef()),
                    captureRevision -> {
                        String sampleKey = EvaluationKeys.sampleKey(
                                workspaceId,
                                normalizedDiagnosisId,
                                normalizedScenario,
                                EvidenceEvaluationSample.SourcePlatform.GUANCE,
                                plan.searchTerm(),
                                plan.window(),
                                occurredAt,
                                captureRevision);
                        return EvidenceEvaluationSample.capturedFormal(
                                "eval-" + sampleKey.substring(0, 24),
                                sampleKey,
                                captureIdentityKey,
                                captureRevision,
                                normalizedDiagnosisId,
                                incident.system(),
                                incident.service(),
                                normalizedScenario,
                                preview,
                                modelInput.fingerprint(),
                                occurredAt,
                                diagnosis.fixtureMode(),
                                target.pilotPlanVersion(),
                                target.playbookVersionRef(),
                                normalizedActor,
                                Instant.now(clock));
                    });
        } catch (IllegalArgumentException invalidPreview) {
            throw conflict("Guance Evidence Spine was not observed: " + invalidPreview.getMessage());
        }
    }

    /** Captures the same frozen contract from the fixture-confined recorded Replay source. */
    public EvidenceEvaluationSampleStore.StoredSample captureRecordedReplay(
            long workspaceId,
            String diagnosisId,
            String actor) {
        validateWorkspace(workspaceId);
        if (replayService == null || replayCapabilityService == null) {
            throw conflict("Recorded Replay evaluation capture is not configured");
        }
        String normalizedDiagnosisId = required(diagnosisId, "diagnosisId");
        String normalizedActor = required(actor, "actor");
        RecordedReplayEvaluationCapability capability =
                replayCapabilityService.inspect(workspaceId, normalizedDiagnosisId);
        if (capability == null || !capability.available()) {
            String reason = capability == null
                    ? "capability unavailable"
                    : capability.reasonCode();
            throw conflict("server-owned Replay target is not ready: " + reason);
        }
        String normalizedScenario = structuredKey(capability.scenarioKey(), "scenarioKey");
        EvidenceSpinePlan plan = safePlan(capability.searchTerm(), capability.window());

        StoredDiagnosis storedDiagnosis =
                persistenceService.get(workspaceId, normalizedDiagnosisId);
        Diagnosis diagnosis = storedDiagnosis.diagnosis();
        IncidentContext incident = diagnosis.incident();
        Instant occurredAt = incident.occurredAt() == null
                ? Instant.now(clock)
                : incident.occurredAt();
        String captureIdentityKey = EvaluationKeys.captureIdentityKey(
                workspaceId,
                normalizedDiagnosisId,
                normalizedScenario,
                EvidenceEvaluationSample.SourcePlatform.RECORDED_REPLAY,
                plan.searchTerm(),
                plan.window(),
                occurredAt);
        SopSynthesisPreview preview = replayService.preview(
                workspaceId,
                new SopSynthesisRequest(
                        incident.system(),
                        incident.service(),
                        plan.searchTerm(),
                        plan.window(),
                        occurredAt));
        EvaluationModelInputFactory.FingerprintedInput modelInput = modelInputFactory.create(
                incident.system(), incident.service(), normalizedScenario, preview);
        try {
            return persistRevision(
                    workspaceId,
                    captureIdentityKey,
                    modelInput.fingerprint(),
                    null,
                    captureRevision -> {
                        String sampleKey = EvaluationKeys.sampleKey(
                                workspaceId,
                                normalizedDiagnosisId,
                                normalizedScenario,
                                EvidenceEvaluationSample.SourcePlatform.RECORDED_REPLAY,
                                plan.searchTerm(),
                                plan.window(),
                                occurredAt,
                                captureRevision);
                        return EvidenceEvaluationSample.capturedReplay(
                                "eval-" + sampleKey.substring(0, 24),
                                sampleKey,
                                captureIdentityKey,
                                captureRevision,
                                normalizedDiagnosisId,
                                incident.system(),
                                incident.service(),
                                normalizedScenario,
                                preview,
                                modelInput.fingerprint(),
                                occurredAt,
                                diagnosis.fixtureMode(),
                                normalizedActor,
                                Instant.now(clock));
                    });
        } catch (IllegalArgumentException invalidPreview) {
            throw conflict(
                    "Recorded Replay Evidence Spine was not observed: "
                            + invalidPreview.getMessage());
        }
    }

    /**
     * Finalizes a structural human oracle after the linked Diagnosis has an authoritative closure.
     * Outcome data comes from the server-side aggregate rather than the browser request.
     */
    public EvidenceEvaluationSample finalizeReference(
            long workspaceId,
            String sampleId,
            int expectedVersion,
            List<String> requiredStepIntents,
            List<String> forbiddenStepIntents,
            EvidenceEvaluationSample.ExpectedDisposition expectedDisposition,
            EvidenceEvaluationSample.HumanBaseline humanBaseline,
            String actor) {
        validateWorkspace(workspaceId);
        if (expectedVersion < 0) {
            throw invalid("expectedVersion must not be negative");
        }
        String normalizedSampleId = required(sampleId, "sampleId");
        String normalizedActor = required(actor, "actor");
        if (expectedDisposition == null) {
            throw invalid("expectedDisposition is required");
        }
        EvidenceEvaluationSample sample = store.get(workspaceId, normalizedSampleId)
                .orElseThrow(() -> notFound(
                        "evaluation sample not found: " + normalizedSampleId));

        if (humanBaseline != null && !sample.formalPilotSample()) {
            throw invalid(
                    "a human time baseline is only valid for a real Guance Diagnosis; "
                            + "Recorded Replay and fixture samples only measure correctness");
        }

        List<String> required = intentKeys(requiredStepIntents, "requiredStepIntents");
        List<String> forbidden = intentKeys(forbiddenStepIntents, "forbiddenStepIntents");
        if (required.stream().anyMatch(forbidden::contains)) {
            throw invalid("required and forbidden intent keys must be disjoint");
        }

        StoredDiagnosis storedDiagnosis =
                persistenceService.get(workspaceId, sample.diagnosisId());
        Diagnosis diagnosis = storedDiagnosis.diagnosis();
        ClosureRecord closure = diagnosis.closure();
        if (diagnosis.status() != DiagnosisStatus.CLOSED || closure == null) {
            throw conflict(
                    "reference finalization requires a closed Diagnosis with an outcome");
        }

        List<String> requiredEvidenceKinds = new ArrayList<>(
                List.of("log_search", "log_trace_bundle"));
        if (sample.evidence().contrast().available()) {
            requiredEvidenceKinds.add("contrast_sample");
        }
        ReferenceSolution reference = new ReferenceSolution(
                sample.sampleId() + "/reference/v1",
                sample.scenarioKey(),
                required,
                forbidden,
                ordering(required),
                List.copyOf(requiredEvidenceKinds));
        EvidenceEvaluationSample.OutcomeSnapshot outcome;
        try {
            outcome = new EvidenceEvaluationSample.OutcomeSnapshot(
                    closure.outcome(),
                    closure.summary(),
                    closure.recoveryVerified(),
                    closure.closedAt());
        } catch (IllegalArgumentException invalidClosure) {
            throw conflict(
                    "linked Diagnosis closure is not business-safe and complete for evaluation: "
                            + invalidClosure.getMessage());
        }

        if (sample.referenceStatus()
                == EvidenceEvaluationSample.ReferenceStatus.READY_FOR_EVALUATION) {
            if (reference.equals(sample.referenceSolution())
                    && expectedDisposition == sample.expectedDisposition()
                    && outcome.equals(sample.outcome())) {
                return sample;
            }
            throw conflict("evaluation sample reference is already finalized");
        }
        if (sample.version() != expectedVersion) {
            throw conflict("evaluation sample version conflict");
        }

        EvidenceEvaluationSample finalized = sample.finalizeReference(
                reference,
                expectedDisposition,
                humanBaseline,
                outcome,
                normalizedActor,
                Instant.now(clock));
        return store.finalizeReference(workspaceId, finalized, expectedVersion);
    }

    /** Backward-compatible default for existing P1 tests and callers. */
    public EvidenceEvaluationSample finalizeReference(
            long workspaceId,
            String sampleId,
            int expectedVersion,
            List<String> requiredStepIntents,
            List<String> forbiddenStepIntents,
            String actor) {
        return finalizeReference(
                workspaceId,
                sampleId,
                expectedVersion,
                requiredStepIntents,
                forbiddenStepIntents,
                EvidenceEvaluationSample.ExpectedDisposition.DRAFT,
                null,
                actor);
    }

    public EvidenceEvaluationSampleLedger list(
            long workspaceId,
            String diagnosisId,
            int limit) {
        validateWorkspace(workspaceId);
        String normalizedDiagnosisId = diagnosisId == null || diagnosisId.isBlank()
                ? null
                : diagnosisId.trim();
        int capped = Math.min(Math.max(limit, 1), 200);
        return EvidenceEvaluationSampleLedger.from(
                store.list(workspaceId, normalizedDiagnosisId, capped));
    }

    /**
     * The shadow cohort's second answer: what a person used to spend, next to
     * what the machine spends.
     *
     * <p>The join lives here because this service owns the sample ledger and the
     * run ledger is a separate one; {@link NorthStarComparison} only does the
     * arithmetic, and is deliberately kept ignorant of how either was stored.</p>
     */
    public NorthStarComparison northStar(
            long workspaceId,
            String diagnosisId,
            int limit,
            List<BaselineEvaluationRun> runs) {
        List<EvidenceEvaluationSample> realSamples =
                list(workspaceId, diagnosisId, limit).samples().stream()
                        .filter(EvidenceEvaluationSample::formalPilotSample)
                        .toList();
        LinkedHashSet<String> realSampleIds = new LinkedHashSet<>();
        realSamples.forEach(sample -> realSampleIds.add(sample.sampleId()));
        List<BaselineEvaluationRun> realRuns = List.copyOf(
                        runs == null ? List.of() : runs)
                .stream()
                .filter(run -> run.sourcePlatform()
                        == EvidenceEvaluationSample.SourcePlatform.GUANCE)
                .filter(run -> !run.evidenceFixtureMode() && !run.diagnosisFixtureMode())
                .filter(run -> realSampleIds.contains(run.sampleId()))
                .toList();
        NorthStarComparison comparison = NorthStarComparison.from(
                realSamples.size(),
                realSamples.stream()
                        .map(EvidenceEvaluationSample::humanBaseline)
                        .filter(baseline -> baseline != null)
                        .toList(),
                realRuns);
        List<String> caveats = new ArrayList<>();
        caveats.add(
                "耗时效果只统计真实 Guance 且非演练样本；Recorded Replay 和 fixture "
                        + "只参与「准不准」回归");
        caveats.addAll(comparison.caveats());
        return new NorthStarComparison(
                comparison.sampleCount(),
                comparison.withHumanBaseline(),
                comparison.measured(),
                comparison.estimated(),
                comparison.machineP50Ms(),
                comparison.machineP95Ms(),
                comparison.machineRunCount(),
                caveats);
    }

    private FrozenCaptureTarget frozenCaptureTarget(
            long workspaceId,
            StoredDiagnosis storedDiagnosis) {
        Diagnosis diagnosis = storedDiagnosis.diagnosis();
        if (diagnosis.fixtureMode()) {
            throw conflict("fixture Diagnosis cannot produce a formal Guance sample");
        }
        if (diagnosis.rehearsal()) {
            throw conflict("rehearsal Diagnosis cannot produce a formal Guance sample");
        }
        Integer pilotPlanVersion = storedDiagnosis.pilotPlanVersion();
        if (pilotPlanVersion == null) {
            throw conflict("Diagnosis is not enrolled in the production pilot");
        }
        PlaybookVersionRef ref = diagnosis.sourcePlaybookVersionRef();
        if (ref == null) {
            throw conflict("Diagnosis carries no frozen Playbook version");
        }
        if (playbookVersions == null) {
            throw conflict("frozen Playbook version resolution is not configured");
        }
        ApprovedPlaybookVersion frozen = playbookVersions.findByRef(workspaceId, ref)
                .orElseThrow(() -> conflict(
                        "the frozen Playbook version is no longer readable"));
        String diagnosisSelector = diagnosis.sopKey();
        SopEntry playbook = frozen.playbook();
        if (diagnosisSelector == null
                || !diagnosisSelector.equals(frozen.selectorKey())
                || !diagnosisSelector.equals(playbook.routingKey())) {
            throw conflict("the frozen Playbook version does not match the Diagnosis selector");
        }
        if (playbook.scenarioScoped()) {
            throw conflict(
                    "formal Guance sample capture requires D20 scenario-scoped binding and acceptance");
        }
        PlaybookVersionRef active = playbookVersions.activeRef(
                        workspaceId, diagnosisSelector)
                .orElseThrow(() -> conflict(
                        "the Diagnosis Playbook is no longer the active authority"));
        if (!ref.equals(active)) {
            throw conflict("the Diagnosis Playbook is no longer the active authority");
        }
        EvidenceSpinePlan plan;
        try {
            plan = EvidenceSpinePlanResolver.resolve(playbook);
        } catch (IllegalArgumentException invalidPlan) {
            throw conflict("the frozen Evidence Spine is invalid: " + invalidPlan.getMessage());
        }
        if (plan == null) {
            throw conflict("the frozen Playbook does not declare the full Evidence Spine");
        }
        String scenario = playbook.scenarioKey() == null
                ? plan.searchTerm()
                : playbook.scenarioKey();
        return new FrozenCaptureTarget(
                structuredKey(scenario, "frozen scenarioKey"),
                plan,
                pilotPlanVersion,
                ref,
                diagnosisSelector);
    }

    private void requireCurrentPilot(
            long workspaceId,
            FrozenCaptureTarget target,
            IncidentContext incident) {
        if (pilotPlans == null) {
            throw conflict("production pilot revalidation is not configured");
        }
        Integer current = pilotPlans.enrollmentVersion(
                workspaceId, incident.system(), incident.service(), false);
        if (current == null || current != target.pilotPlanVersion()) {
            throw conflict("pilot plan changed during formal Guance sample capture");
        }
    }

    private void revalidateFormalAuthority(
            long workspaceId,
            IncidentContext incident,
            FrozenCaptureTarget target,
            GuanceEvidenceAcceptance acceptedBefore) {
        requireCurrentPilot(workspaceId, target, incident);
        PlaybookVersionRef active = playbookVersions.activeRef(
                        workspaceId, target.selectorKey())
                .orElseThrow(() -> conflict(
                        "the Diagnosis Playbook changed during Guance observation"));
        if (!target.playbookVersionRef().equals(active)) {
            throw conflict("the Diagnosis Playbook changed during Guance observation");
        }
        GuanceEvidenceAcceptance acceptedAfter = acceptanceService.requireAccepted(
                workspaceId, incident.system(), incident.service());
        if (acceptedBefore == null
                || acceptedAfter == null
                || !sameScope(incident.system(), acceptedBefore.system())
                || !sameScope(incident.service(), acceptedBefore.service())
                || !sameScope(incident.system(), acceptedAfter.system())
                || !sameScope(incident.service(), acceptedAfter.service())
                || !acceptedBefore.acceptanceId().equals(acceptedAfter.acceptanceId())
                || !acceptedBefore.bindingFingerprint()
                        .equals(acceptedAfter.bindingFingerprint())) {
            throw conflict("Guance owner acceptance changed during observation");
        }
    }

    private boolean sameScope(String left, String right) {
        return left != null && right != null
                && left.trim().equalsIgnoreCase(right.trim());
    }

    private EvidenceSpinePlan safePlan(String searchTerm, String window) {
        try {
            return new EvidenceSpinePlan(
                    "T8-GUANCE-LOG-SEARCH",
                    "T8-GUANCE-TRACE-BUNDLE",
                    "T8-GUANCE-CONTRAST-SAMPLE",
                    searchTerm,
                    window);
        } catch (IllegalArgumentException invalidPlan) {
            throw invalid(invalidPlan.getMessage());
        }
    }

    private List<String> intentKeys(List<String> values, String field) {
        if (values == null || values.isEmpty()) {
            throw invalid(field + " must contain between 1 and " + MAX_INTENTS + " intent keys");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String key = value == null ? "" : value.trim();
            if (!STRUCTURED_KEY.matcher(key).matches()) {
                throw invalid(field + " contains an invalid structured intent key");
            }
            normalized.add(key);
        }
        if (normalized.size() > MAX_INTENTS) {
            throw invalid(field + " must contain between 1 and " + MAX_INTENTS + " intent keys");
        }
        return List.copyOf(normalized);
    }

    private List<ReferenceSolution.OrderingConstraint> ordering(List<String> required) {
        List<ReferenceSolution.OrderingConstraint> constraints =
                new ArrayList<>(Math.max(0, required.size() - 1));
        for (int index = 0; index + 1 < required.size(); index++) {
            constraints.add(new ReferenceSolution.OrderingConstraint(
                    required.get(index), required.get(index + 1)));
        }
        return List.copyOf(constraints);
    }

    private String structuredKey(String value, String field) {
        String normalized = required(value, field);
        if (!STRUCTURED_KEY.matcher(normalized).matches()) {
            throw invalid(field + " must be a structured intent key");
        }
        return normalized;
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " must not be blank");
        }
        return value.trim();
    }

    private void validateWorkspace(long workspaceId) {
        if (workspaceId <= 0) {
            throw invalid("workspaceId must be positive");
        }
    }

    private boolean sameFrozenInput(
            EvidenceEvaluationSample sample,
            String modelInputHash,
            FormalSampleIdentity formalIdentity) {
        if (sample == null
                || modelInputHash == null
                || !modelInputHash.equals(sample.modelInputHash())) {
            return false;
        }
        return formalIdentity == null
                || sample.formalPilotSample()
                && formalIdentity.pilotPlanVersion() == sample.pilotPlanVersion()
                && formalIdentity.playbookVersionRef()
                        .equals(sample.sourcePlaybookVersionRef());
    }

    private EvidenceEvaluationSampleStore.StoredSample persistRevision(
            long workspaceId,
            String captureIdentityKey,
            String modelInputHash,
            FormalSampleIdentity formalIdentity,
            RevisionFactory revisionFactory) {
        Optional<EvidenceEvaluationSample> latest =
                store.findLatestByCaptureIdentity(workspaceId, captureIdentityKey);
        for (int attempt = 0; attempt < MAX_REVISION_ATTEMPTS; attempt++) {
            if (latest.isPresent()
                    && sameFrozenInput(latest.get(), modelInputHash, formalIdentity)) {
                return new EvidenceEvaluationSampleStore.StoredSample(
                        latest.orElseThrow(), false);
            }
            EvidenceEvaluationSample candidate = revisionFactory.create(nextRevision(latest));
            EvidenceEvaluationSampleStore.StoredSample stored =
                    store.saveOrGet(workspaceId, candidate);
            EvidenceEvaluationSample winner = stored.sample();
            if (!captureIdentityKey.equals(winner.captureIdentityKey())) {
                throw conflict("evaluation sample revision identity conflict");
            }
            if (sameFrozenInput(winner, modelInputHash, formalIdentity)) {
                return stored;
            }
            Optional<EvidenceEvaluationSample> refreshed =
                    store.findLatestByCaptureIdentity(workspaceId, captureIdentityKey);
            latest = refreshed.isPresent()
                    && refreshed.get().captureRevision() >= winner.captureRevision()
                    ? refreshed
                    : Optional.of(winner);
        }
        throw conflict("evaluation sample revision allocation is contended; retry capture");
    }

    private int nextRevision(Optional<EvidenceEvaluationSample> latest) {
        return latest.map(sample -> Math.addExact(sample.captureRevision(), 1))
                .orElse(1);
    }

    private record FrozenCaptureTarget(
            String scenarioKey,
            EvidenceSpinePlan plan,
            int pilotPlanVersion,
            PlaybookVersionRef playbookVersionRef,
            String selectorKey) {
    }

    private record FormalSampleIdentity(
            int pilotPlanVersion,
            PlaybookVersionRef playbookVersionRef) {
    }

    @FunctionalInterface
    private interface RevisionFactory {
        EvidenceEvaluationSample create(int captureRevision);
    }

    private MateClawException invalid(String message) {
        return new MateClawException(
                "err.troubleshooting.invalid_request", 400, message);
    }

    private MateClawException notFound(String message) {
        return new MateClawException(
                "err.troubleshooting.evaluation_sample_not_found", 404, message);
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.evaluation_sample_conflict", 409, message);
    }
}
