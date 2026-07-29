package vip.mate.troubleshooting.evaluation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.EvidenceSpinePlan;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreviewService;
import vip.mate.troubleshooting.model.ClosureRecord;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.synthesis.ReferenceSolution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Captures and finalizes the human-owned T8 historical sample ledger. */
@Service
public class EvidenceEvaluationSampleService {

    private static final Pattern STRUCTURED_KEY =
            Pattern.compile("[a-z][a-z0-9_:-]{1,63}");
    private static final List<String> REQUIRED_EVIDENCE_KINDS =
            List.of("log_search", "log_trace_bundle", "contrast_sample");
    private static final int MAX_INTENTS = 20;

    private final GuanceEvidenceSpinePreviewService previewService;
    private final TroubleshootingPersistenceService persistenceService;
    private final EvidenceEvaluationSampleStore store;
    private final Clock clock;

    @Autowired
    public EvidenceEvaluationSampleService(
            GuanceEvidenceSpinePreviewService previewService,
            TroubleshootingPersistenceService persistenceService,
            EvidenceEvaluationSampleStore store) {
        this(previewService, persistenceService, store, Clock.systemUTC());
    }

    EvidenceEvaluationSampleService(
            GuanceEvidenceSpinePreviewService previewService,
            TroubleshootingPersistenceService persistenceService,
            EvidenceEvaluationSampleStore store,
            Clock clock) {
        this.previewService = previewService;
        this.persistenceService = persistenceService;
        this.store = store;
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
        Instant occurredAt = incident.occurredAt() == null
                ? Instant.now(clock)
                : incident.occurredAt();
        String sampleKey = sampleKey(
                workspaceId,
                normalizedDiagnosisId,
                normalizedScenario,
                plan.searchTerm(),
                plan.window(),
                occurredAt);

        Optional<EvidenceEvaluationSample> existing =
                store.findBySampleKey(workspaceId, sampleKey);
        if (existing.isPresent()) {
            return new EvidenceEvaluationSampleStore.StoredSample(existing.get(), false);
        }

        GuanceEvidenceSpinePreview preview = previewService.preview(
                workspaceId,
                incident.system(),
                incident.service(),
                plan.searchTerm(),
                plan.window(),
                occurredAt);
        if (preview.stage() == GuanceEvidenceSpinePreview.Stage.BLOCKED) {
            throw conflict("Guance Evidence Spine was not observed; no sample was persisted");
        }

        EvidenceEvaluationSample sample;
        try {
            sample = EvidenceEvaluationSample.captured(
                    "eval-" + sampleKey.substring(0, 24),
                    sampleKey,
                    normalizedDiagnosisId,
                    incident.system(),
                    incident.service(),
                    normalizedScenario,
                    preview,
                    diagnosis.fixtureMode(),
                    normalizedActor,
                    Instant.now(clock));
        } catch (IllegalArgumentException invalidPreview) {
            throw conflict("Guance Evidence Spine was not observed: " + invalidPreview.getMessage());
        }
        return store.saveOrGet(workspaceId, sample);
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
            String actor) {
        validateWorkspace(workspaceId);
        if (expectedVersion < 0) {
            throw invalid("expectedVersion must not be negative");
        }
        String normalizedSampleId = required(sampleId, "sampleId");
        String normalizedActor = required(actor, "actor");
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

        ReferenceSolution reference = new ReferenceSolution(
                sample.sampleId() + "/reference/v1",
                sample.scenarioKey(),
                required,
                forbidden,
                ordering(required),
                REQUIRED_EVIDENCE_KINDS);
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
                    && outcome.equals(sample.outcome())) {
                return sample;
            }
            throw conflict("evaluation sample reference is already finalized");
        }
        if (sample.version() != expectedVersion) {
            throw conflict("evaluation sample version conflict");
        }

        EvidenceEvaluationSample finalized = sample.finalizeReference(
                reference, outcome, normalizedActor, Instant.now(clock));
        return store.finalizeReference(workspaceId, finalized, expectedVersion);
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

    private String sampleKey(
            long workspaceId,
            String diagnosisId,
            String scenarioKey,
            String searchTerm,
            String window,
            Instant occurredAt) {
        String raw = workspaceId
                + "\u001f" + diagnosisId
                + "\u001f" + scenarioKey
                + "\u001f" + searchTerm
                + "\u001f" + window
                + "\u001f" + occurredAt.toString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
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
