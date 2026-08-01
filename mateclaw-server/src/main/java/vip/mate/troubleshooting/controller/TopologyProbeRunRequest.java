package vip.mate.troubleshooting.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TopologyProbeRunRequest(
        @NotBlank @Size(max = 128) String topologyId) {
}
