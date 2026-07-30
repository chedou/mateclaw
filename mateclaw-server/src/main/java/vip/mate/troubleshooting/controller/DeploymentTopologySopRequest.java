package vip.mate.troubleshooting.controller;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

/** User-supplied topology snapshot; credentials and source query text are never accepted. */
public record DeploymentTopologySopRequest(@NotNull JsonNode snapshot) {
}
