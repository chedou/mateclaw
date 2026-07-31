package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
import vip.mate.troubleshooting.synthesis.KnowledgeReviewInbox;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewInboxService;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewApproval;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewDeprecation;
import vip.mate.troubleshooting.synthesis.KnowledgeOrigin;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewState;
import vip.mate.troubleshooting.synthesis.KnowledgeReviewWorkflowService;
import vip.mate.troubleshooting.synthesis.ManualPlaybookReplayAttestation;
import vip.mate.troubleshooting.synthesis.ManualPlaybookReplayService;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;
import vip.mate.troubleshooting.synthesis.PlaybookSynthesisResult;
import vip.mate.troubleshooting.synthesis.SopSynthesisPreview;
import vip.mate.troubleshooting.synthesis.SopSynthesisService;
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
 * <p>Registration is deliberately create-only. Stable source IDs are unique,
 * while multiple immutable candidates may propose later versions for one
 * selector. Only the version registry may choose a single active authority.</p>
 */
@RestController
@RequestMapping("/api/v1/troubleshooting/sops")
@RequiredArgsConstructor
public class SopManagementController {

    private static final long DEFAULT_WORKSPACE_ID = 1L;
    private static final int DEFAULT_PAGE_SIZE = 100;

    private final TroubleshootingSopPersistenceService sopPersistence;
    private final TroubleshootingPersistenceService persistence;
    private final SopSynthesisService synthesisService;
    private final KnowledgeReviewInboxService reviewInboxService;
    private final KnowledgeReviewWorkflowService reviewWorkflow;
    private final ManualPlaybookReplayService manualReplayService;

    /**
     * Previews the first three learning-loop stages without invoking a model or
     * creating a SOP candidate.
     *
     * <p>The response contains only bounded, redacted canonical evidence
     * references and the deterministic call-chain skeleton. It never exposes
     * the full raw log bundle or a rendered platform query. The current preview
     * is fixture-only and cannot invoke a live observability adapter.</p>
     */
    @PostMapping("/synthesis/preview")
    @RequireWorkspaceRole("admin")
    public R<SopSynthesisPreview> previewSynthesis(
            @Valid @RequestBody SopSynthesisPreviewRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(synthesisService.preview(
                resolveWorkspace(workspaceId), request.toDomainRequest()));
    }

    /**
     * Runs the fixture-confined P1 evidence-to-draft lane.
     *
     * <p>Success creates or reuses a review-only evidence-derived candidate.
     * Model rejection, explicit abstention, and deterministic validation
     * rejection are returned as typed results and never write a candidate.
     * There is intentionally no approval parameter or promotion side effect.</p>
     */
    @PostMapping("/synthesis/candidates")
    @RequireWorkspaceRole("admin")
    public R<PlaybookSynthesisResult> generateSynthesisCandidate(
            @Valid @RequestBody PlaybookSynthesisGenerateRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(synthesisService.generate(
                resolveWorkspace(workspaceId), request.toDomainRequest()));
    }

    /**
     * Unifies the three persisted candidate lanes for the knowledge review desk.
     *
     * <p>The response joins source records with their independent review
     * states. Absence from {@code reviewStates} means CANDIDATE/v0. Publication
     * state is never reused as review state, and this endpoint cannot promote a
     * candidate.</p>
     */
    @GetMapping("/review-inbox")
    @RequireWorkspaceRole("admin")
    public R<KnowledgeReviewInbox> reviewInbox(
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long resolvedWorkspace = resolveWorkspace(workspaceId);
        int resolvedLimit = limit == null ? DEFAULT_PAGE_SIZE : limit;
        return R.ok(reviewInboxService.read(resolvedWorkspace, resolvedLimit));
    }

    /**
     * Runs the bundled fixed replay suite against one exact manual candidate.
     *
     * <p>The request supplies no fixture, expected answer, proof or candidate
     * content. The service resolves all of them from immutable server-owned
     * state and persists only bounded counters plus double fingerprints.</p>
     */
    @PostMapping("/review-inbox/manual/{sourceRecordId}/replay")
    @RequireWorkspaceRole("admin")
    public R<ManualPlaybookReplayAttestation> replayManualCandidate(
            @PathVariable String sourceRecordId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(manualReplayService.run(
                resolveWorkspace(workspaceId), sourceRecordId, currentActor()));
    }

    /** Downloads a server-owned candidate example without exposing replay cases. */
    @GetMapping("/review-inbox/manual/example")
    @RequireWorkspaceRole("admin")
    public R<SopEntry> manualCandidateExample(@RequestParam String selectorKey) {
        return R.ok(manualReplayService.exampleCandidate(selectorKey));
    }

    /** Starts an audited review from the virtual CANDIDATE/v0 state. */
    @PostMapping("/review-inbox/{origin}/{sourceRecordId}/start")
    @RequireWorkspaceRole("admin")
    public R<KnowledgeReviewState> startReview(
            @PathVariable KnowledgeOrigin origin,
            @PathVariable String sourceRecordId,
            @Valid @RequestBody ReviewDecision request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(reviewWorkflow.start(
                resolveWorkspace(workspaceId),
                origin,
                sourceRecordId,
                request.expectedVersion(),
                currentActor(),
                request.reason()));
    }

    /**
     * Records a rejection against the exact IN_REVIEW version.
     *
     * <p>Rejection and approval are separate commands so neither can be
     * represented as a caller-controlled generic status mutation.</p>
     */
    @PostMapping("/review-inbox/{origin}/{sourceRecordId}/reject")
    @RequireWorkspaceRole("admin")
    public R<KnowledgeReviewState> rejectReview(
            @PathVariable KnowledgeOrigin origin,
            @PathVariable String sourceRecordId,
            @Valid @RequestBody ReviewDecision request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(reviewWorkflow.reject(
                resolveWorkspace(workspaceId),
                origin,
                sourceRecordId,
                request.expectedVersion(),
                currentActor(),
                request.reason()));
    }

    /**
     * Creates a new approved Playbook version from an eligible review.
     *
     * <p>The service re-reads current source qualification and routeable
     * content, then compares the selector authority frozen at review start.
     * The request cannot provide Playbook JSON, owner proof, replay proof or
     * an actor.</p>
     */
    @PostMapping("/review-inbox/{origin}/{sourceRecordId}/approve")
    @RequireWorkspaceRole("admin")
    public R<KnowledgeReviewApproval> approveReview(
            @PathVariable KnowledgeOrigin origin,
            @PathVariable String sourceRecordId,
            @Valid @RequestBody ReviewDecision request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(reviewWorkflow.approve(
                resolveWorkspace(workspaceId),
                origin,
                sourceRecordId,
                request.expectedVersion(),
                currentActor(),
                request.reason()));
    }

    /** Retires the exact active Playbook version created by an approved review. */
    @PostMapping("/review-inbox/{origin}/{sourceRecordId}/deprecate")
    @RequireWorkspaceRole("admin")
    public R<KnowledgeReviewDeprecation> deprecateReview(
            @PathVariable KnowledgeOrigin origin,
            @PathVariable String sourceRecordId,
            @Valid @RequestBody ReviewDecision request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(reviewWorkflow.deprecate(
                resolveWorkspace(workspaceId),
                origin,
                sourceRecordId,
                request.expectedVersion(),
                currentActor(),
                request.reason()));
    }

    /** Audited retirement for a V186 legacy migration version without review_id. */
    @PostMapping("/versions/{playbookId}/deprecate")
    @RequireWorkspaceRole("admin")
    public R<ApprovedPlaybookVersion> deprecateLegacyVersion(
            @PathVariable String playbookId,
            @Valid @RequestBody LegacyVersionDecision request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(reviewWorkflow.deprecateLegacy(
                resolveWorkspace(workspaceId),
                playbookId,
                request.expectedPlaybookVersion(),
                currentActor(),
                request.reason()));
    }

    /**
     * Registers an immutable manual source. Duplicate source IDs fail with 409.
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
        SopEntry sop = sopPersistence.findLatest(
                resolveWorkspace(workspaceId), system, errorCode);
        if (sop == null) {
            throw new MateClawException(
                    "err.troubleshooting.sop_not_found", 404,
                    "no SOP registered for " + system + ":" + errorCode);
        }
        return R.ok(sop);
    }

    /** Reads the exact manual source row even when a newer version owns its selector. */
    @GetMapping("/by-id/{sopId}")
    @RequireWorkspaceRole("admin")
    public R<SopEntry> getById(
            @PathVariable String sopId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        SopEntry sop = sopPersistence.findBySopId(
                resolveWorkspace(workspaceId), sopId);
        if (sop == null) {
            throw new MateClawException(
                    "err.troubleshooting.sop_not_found", 404,
                    "no SOP registered with id " + sopId);
        }
        return R.ok(sop);
    }

    /**
     * Retires an approved SOP version.
     *
     * <p>The compatibility route deliberately rejects candidate approval.
     * Promotion must later go through the source eligibility and versioned
     * replacement command; a generic status mutation cannot make knowledge
     * authoritative.</p>
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

    /** Compatibility status command; only {@code deprecated} is accepted. */
    public record StatusChange(@NotBlank String status) {}

    /** The actor is server-derived; callers only provide concurrency and rationale. */
    public record ReviewDecision(
            @Min(0) int expectedVersion,
            @NotBlank @Size(max = 1000) String reason) {}

    /** Exact migration version plus an audited retirement reason. */
    public record LegacyVersionDecision(
            @Min(1) int expectedPlaybookVersion,
            @NotBlank @Size(max = 1000) String reason) {}

    private long resolveWorkspace(Long workspaceId) {
        return workspaceId == null ? DEFAULT_WORKSPACE_ID : workspaceId;
    }

    private String currentActor() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new MateClawException(
                    "err.troubleshooting.actor_required",
                    401,
                    "knowledge review changes require an authenticated operator");
        }
        return authentication.getName();
    }
}
