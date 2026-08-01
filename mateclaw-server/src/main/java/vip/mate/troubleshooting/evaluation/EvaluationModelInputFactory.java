package vip.mate.troubleshooting.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpineObservation;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.synthesis.SopSynthesisPreview;
import vip.mate.troubleshooting.synthesis.SynthesisModelInput;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Builds and fingerprints the exact bounded input used by a T8 baseline model call. */
@Component
public final class EvaluationModelInputFactory {

    private final ObjectMapper objectMapper;

    public EvaluationModelInputFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public FingerprintedInput create(
            String system,
            String service,
            String scenarioKey,
            GuanceEvidenceSpineObservation observation) {
        if (observation == null
                || observation.preview().stage() == GuanceEvidenceSpinePreview.Stage.BLOCKED
                || observation.skeleton() == null) {
            throw new IllegalArgumentException(
                    "an observed Evidence Spine with a bounded skeleton is required");
        }

        List<SynthesisModelInput.EvidenceDescriptor> evidence = new ArrayList<>(3);
        for (GuanceEvidenceSpinePreview.Step step : observation.preview().steps()) {
            if (step.status()
                    == GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED) {
                evidence.add(new SynthesisModelInput.EvidenceDescriptor(
                        step.evidenceRef(), step.signalKind()));
            }
        }
        SynthesisModelInput input = new SynthesisModelInput(
                system,
                service,
                scenarioKey,
                List.copyOf(evidence),
                observation.skeleton());
        return new FingerprintedInput(input, fingerprint(input));
    }

    public FingerprintedInput create(
            String system,
            String service,
            String scenarioKey,
            SopSynthesisPreview preview) {
        if (preview == null
                || preview.stage() != SopSynthesisPreview.Stage.READY_FOR_MODEL
                || preview.skeleton() == null
                || !preview.fixtureMode()) {
            throw new IllegalArgumentException(
                    "a recorded Replay preview with a bounded skeleton is required");
        }
        List<SynthesisModelInput.EvidenceDescriptor> evidence = new ArrayList<>(3);
        evidence.add(new SynthesisModelInput.EvidenceDescriptor(
                preview.searchEvidence().queryId(), "log_search"));
        evidence.add(new SynthesisModelInput.EvidenceDescriptor(
                preview.traceEvidence().queryId(), "log_trace_bundle"));
        if (preview.contrastEvidence() != null) {
            evidence.add(new SynthesisModelInput.EvidenceDescriptor(
                    preview.contrastEvidence().queryId(), "contrast_sample"));
        }
        SynthesisModelInput input = new SynthesisModelInput(
                system,
                service,
                scenarioKey,
                List.copyOf(evidence),
                preview.skeleton());
        return new FingerprintedInput(input, fingerprint(input));
    }

    private String fingerprint(SynthesisModelInput input) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(input);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical));
        } catch (JsonProcessingException impossibleInput) {
            throw new IllegalArgumentException(
                    "bounded evaluation input cannot be serialized", impossibleInput);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record FingerprintedInput(
            SynthesisModelInput input,
            String fingerprint) {

        public FingerprintedInput {
            if (input == null
                    || fingerprint == null
                    || !fingerprint.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException(
                        "model input and its SHA-256 fingerprint are required");
            }
        }

        /** Do not render the model-visible skeleton in logs or HTTP diagnostics. */
        @Override
        public String toString() {
            return "FingerprintedInput[fingerprint=" + fingerprint + "]";
        }
    }
}
