package vip.mate.troubleshooting.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Immutable shared-topology import; actor and workspace remain server-owned. */
public record DeploymentTopologyImportRequest(
        @NotBlank @Size(max = 128) String name,
        @NotNull JsonNode snapshot) {
}
