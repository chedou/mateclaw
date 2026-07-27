package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.KnowledgeCandidate;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.service.SopSummary;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

/**
 * Curating the knowledge the deterministic path runs on.
 *
 * <p>Everything here needs {@code manage:troubleshooting} (admin and above),
 * including reads. That is stricter than the diagnosis console on purpose: a
 * SOP decides what a diagnosis concludes and which recovery actions get
 * proposed, so editing one is a change to how the system behaves for every
 * future incident, not a per-case decision.</p>
 *
 * <p>Registration is deliberately create-only. Route keys are unique and a
 * collision fails closed, which is what surfaces the one-code-many-meanings
 * problem in the source knowledge base instead of letting a second author
 * silently overwrite the first.</p>
 */
@RestController
@RequestMapping("/api/v1/troubleshooting/sops")
@RequiredArgsConstructor
public class SopManagementController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;
    private static final int DEFAULT_PAGE_SIZE = 100;

    private final TroubleshootingSopPersistenceService sopPersistence;
    private final TroubleshootingPersistenceService persistence;

    /**
     * Registers a SOP. Fails with 409 when the route is already taken.
     *
     * <p>Callers should register as {@code candidate}; promotion is a separate,
     * reviewed step. A SOP that arrives already approved would put unreviewed
     * knowledge straight onto the deterministic path.</p>
     */
    @PostMapping
    @RequireWorkspaceRole("admin")
    public R<SopEntry> register(
            @Valid @RequestBody SopEntry sop,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(sopPersistence.register(resolveWorkspace(workspaceId), sop));
    }

    /** Browses the route registry. */
    @GetMapping
    @RequireWorkspaceRole("admin")
    public R<List<SopSummary>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String system,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(sopPersistence.list(
                resolveWorkspace(workspaceId), status, system,
                limit == null ? DEFAULT_PAGE_SIZE : limit));
    }

    /** Reads one SOP in full, including its criteria and rules. */
    @GetMapping("/{system}/{errorCode}")
    @RequireWorkspaceRole("admin")
    public R<SopEntry> get(
            @PathVariable String system,
            @PathVariable String errorCode,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        SopEntry sop = sopPersistence.find(resolveWorkspace(workspaceId), system, errorCode);
        if (sop == null) {
            throw new MateClawException(
                    "err.troubleshooting.sop_not_found", 404,
                    "no SOP registered for " + system + ":" + errorCode);
        }
        return R.ok(sop);
    }

    /**
     * Promotes or retires a SOP.
     *
     * <p>Approving is the moment unreviewed knowledge starts driving real
     * conclusions, so it is an explicit, forward-only transition rather than a
     * writable status field.</p>
     */
    @PostMapping("/{system}/{errorCode}/status")
    @RequireWorkspaceRole("admin")
    public R<SopEntry> updateStatus(
            @PathVariable String system,
            @PathVariable String errorCode,
            @Valid @RequestBody StatusChange request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(sopPersistence.updateStatus(
                resolveWorkspace(workspaceId), system, errorCode, request.status()));
    }

    /**
     * The review queue: lessons sedimented when cases closed.
     *
     * <p>Read-only. A candidate is a proposal drawn from one incident, and one
     * incident is not enough evidence to rewrite the knowledge the deterministic
     * path depends on — a reviewer reads these, then registers or promotes a SOP
     * as a separate, deliberate act.</p>
     */
    @GetMapping("/candidates")
    @RequireWorkspaceRole("admin")
    public R<List<KnowledgeCandidate>> candidates(
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(persistence.listKnowledgeCandidates(
                resolveWorkspace(workspaceId), limit == null ? DEFAULT_PAGE_SIZE : limit));
    }

    /** Target of a review decision: {@code approved} or {@code deprecated}. */
    public record StatusChange(@NotBlank String status) {}

    private long resolveWorkspace(Long workspaceId) {
        return workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId;
    }
}
