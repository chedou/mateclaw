package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.EvidenceContractCatalogView;
import vip.mate.troubleshooting.evidence.EvidenceContractDeclaration;
import vip.mate.troubleshooting.evidence.EvidenceContractService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;
import java.util.Map;

/** Workspace method library: reviewed/owned evidence contracts without exposing keys. */
@RestController
@RequestMapping("/api/v1/troubleshooting/evidence/contracts")
@RequiredArgsConstructor
public class EvidenceContractController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;

    private final EvidenceContractService contracts;

    @GetMapping
    @RequireWorkspaceRole("viewer")
    public R<EvidenceContractCatalogView> catalog(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        // Viewers never receive query templates from the list endpoint.
        return R.ok(contracts.catalog(resolveWorkspace(workspaceId), false));
    }

    @GetMapping("/{contractRef}")
    @RequireWorkspaceRole("admin")
    public R<EvidenceContractCatalogView.EvidenceContractView> detail(
            @PathVariable("contractRef") String contractRef,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(contracts.detail(resolveWorkspace(workspaceId), contractRef, true));
    }

    @PutMapping
    @RequireWorkspaceRole("admin")
    public R<EvidenceContractCatalogView.EvidenceContractView> declare(
            @Valid @RequestBody EvidenceContractRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(contracts.declare(
                resolveWorkspace(workspaceId),
                new EvidenceContractDeclaration(
                        request.contractRef(),
                        request.signalKind(),
                        request.scopeType(),
                        request.system(),
                        request.service(),
                        request.scenario(),
                        request.question(),
                        request.summary(),
                        request.namespace(),
                        request.maxRows(),
                        request.queryTemplate(),
                        request.fixedConditions(),
                        request.requiredAssetParameters(),
                        request.fieldAliases(),
                        request.enabled(),
                        request.expectedVersion(),
                        request.reason()),
                currentActor()));
    }

    public record EvidenceContractRequest(
            @NotBlank @Size(max = 128) String contractRef,
            @NotBlank @Size(max = 64) String signalKind,
            @NotBlank @Size(max = 32) String scopeType,
            @Size(max = 128) String system,
            @Size(max = 192) String service,
            @NotBlank @Size(max = 256) String scenario,
            @NotBlank @Size(max = 512) String question,
            @Size(max = 512) String summary,
            @Size(max = 64) String namespace,
            Integer maxRows,
            @NotBlank @Size(max = 8000) String queryTemplate,
            @Size(max = 16) List<@Size(max = 160) String> fixedConditions,
            @Size(max = 32) List<@Size(max = 64) String> requiredAssetParameters,
            @Size(max = 64) Map<@Size(max = 64) String, @Size(max = 64) String> fieldAliases,
            @NotNull Boolean enabled,
            @PositiveOrZero Integer expectedVersion,
            @NotBlank @Size(max = 500) String reason) {
    }

    private long resolveWorkspace(Long workspaceId) {
        long resolved = workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId;
        if (resolved <= 0) {
            throw new MateClawException(
                    "err.troubleshooting.evidence_contract_invalid", 400,
                    "workspaceId must be positive");
        }
        return resolved;
    }

    private String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return "unknown";
        }
        return authentication.getName();
    }
}
