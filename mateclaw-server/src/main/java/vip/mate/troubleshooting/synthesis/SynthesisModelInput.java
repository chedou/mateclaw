package vip.mate.troubleshooting.synthesis;

import java.util.List;

/**
 * The complete model input boundary for P1. The type deliberately cannot carry
 * an {@code EvidenceResult}, rendered DQL, credentials, or the raw trace bundle.
 */
public record SynthesisModelInput(
        String system,
        String service,
        String scenarioKey,
        List<EvidenceDescriptor> evidence,
        LogTraceSkeleton traceSkeleton) {

    public SynthesisModelInput {
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
    }

    public record EvidenceDescriptor(String evidenceId, String signalKind) {
    }
}
