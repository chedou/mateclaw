package vip.mate.troubleshooting.evaluation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.EvidenceSpinePlan;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptanceService;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpineObservation;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreviewService;
import vip.mate.troubleshooting.model.ClosureRecord;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
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
    private final Clock clock;

    @Autowired
    public EvidenceEvaluationSampleService(
            GuanceEvidenceSpinePreviewService previewService,
            TroubleshootingPersistenceService persistenceService,
            EvidenceEvaluationSampleStore store,
            EvaluationModelInputFactory modelInputFactory,
            SopSynthesisService replayService,
            RecordedReplayEvaluationCapabilityService replayCapabilityService,
            GuanceEvidenceAcceptanceService acceptanceService) {
        this(
                previewService,
                persistenceService,
                store,
                modelInputFactory,
                replayService,
                replayCapabilityService,
                acceptanceService,
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
            Clock clock) {
        this.previewService = previewService;
        this.persistenceService = persistenceService;
        this.store = store;
        this.modelInputFactory = modelInputFactory;
        this.replayService = replayService;
        this.replayCapabilityService = replayCapabilityService;
        this.acceptanceService = acceptanceService;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Re-runs the Guance-only Evidence Spine and persists the resulting safe projection.
     * Browser-supplied preview data is never accepted as evidence.
     */
    public EvidenceEvaluationSampleStore.StoredSample capture(
            long workspaceId,
            String diagnosisId,
            String scenarioKey,
            String searchTerm,
            String window,
            String actor) {
        validateWorkspace(workspaceId);
        String normalizedDiagnosisId = required(diagnosisId, "diagnosisId");
        String normalizedScenario = structuredKey(scenarioKey, "scenarioKey");
        String normalizedActor = required(actor, "actor");
        EvidenceSpinePlan plan = safePlan(searchTerm, window);

        StoredDiagnosis storedDiagnosis =
                persistenceService.get(workspaceId, normalizedDiagnosisId);
        Diagnosis diagnosis = storedDiagnosis.diagnosis();
        IncidentContext incident = diagnosis.incident();
        if (acceptanceService == null) {
            throw conflict("T7 owner acceptance is not configured");
        }
        acceptanceService.requireAccepted(
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
        GuanceEvidenceSpinePreview preview = observation.preview();
        if (preview.stage() == GuanceEvidenceSpinePreview.Stage.BLOCKED) {
            throw conflict("Guance Evidence Spine was not observed; no sample was persisted");
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
                        return EvidenceEvaluationSample.captured(
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
        List<EvidenceEvaluationSample> samples =
                list(workspaceId, diagnosisId, limit).samples();
        return NorthStarComparison.from(
                samples.size(),
                samples.stream()
                        .map(EvidenceEvaluationSample::humanBaseline)
                        .filter(baseline -> baseline != null)
                        .toList(),
                runs);
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
            Optional<EvidenceEvaluationSample> latest,
            String modelInputHash) {
        return latest.isPresent()
                && modelInputHash != null
                && modelInputHash.equals(latest.get().modelInputHash());
    }

    private EvidenceEvaluationSampleStore.StoredSample persistRevision(
            long workspaceId,
            String captureIdentityKey,
            String modelInputHash,
            RevisionFactory revisionFactory) {
        Optional<EvidenceEvaluationSample> latest =
                store.findLatestByCaptureIdentity(workspaceId, captureIdentityKey);
        for (int attempt = 0; attempt < MAX_REVISION_ATTEMPTS; attempt++) {
            if (sameFrozenInput(latest, modelInputHash)) {
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
            if (modelInputHash.equals(winner.modelInputHash())) {
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
