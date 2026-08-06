package vip.mate.troubleshooting.evidence;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

/** Admin input for one bounded, non-persistent source query. */
public record EvidenceContractTrialRequest(
        @NotBlank @Size(max = 128) String system,
        @NotBlank @Size(max = 128) String service,
        @NotBlank @Size(max = 128) String contractRef,
        @Size(max = 16) Map<@NotBlank @Size(max = 64) String,
                @NotBlank @Size(max = 256) String> parameters,
        @Size(max = 16) String window,
        Instant occurredAt) {

    public EvidenceContractTrialRequest {
        parameters = Map.copyOf(parameters == null ? Map.of() : parameters);
    }
}
