package vip.mate.troubleshooting.synthesis;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Executes and resolves server-owned replay proof without making a candidate routeable. */
@Service
public class ManualPlaybookReplayService {

    private static final int MAX_SOURCE_ID = 128;
    private static final int MAX_ACTOR = 192;

    private final TroubleshootingSopPersistenceService candidates;
    private final ManualPlaybookReplaySuiteCatalog catalog;
    private final ManualPlaybookReplayFingerprint fingerprints;
    private final ManualPlaybookReplayEvaluator evaluator;
    private final ManualPlaybookReplayAttestationStore store;
    private final Clock clock;
    private final Supplier<String> attestationIds;

    @Autowired
    public ManualPlaybookReplayService(
            TroubleshootingSopPersistenceService candidates,
            ManualPlaybookReplaySuiteCatalog catalog,
            ManualPlaybookReplayFingerprint fingerprints,
            ManualPlaybookReplayEvaluator evaluator,
            ManualPlaybookReplayAttestationStore store) {
        this(
                candidates,
                catalog,
                fingerprints,
                evaluator,
                store,
                Clock.systemUTC(),
                () -> "manual-replay-" + UUID.randomUUID());
    }

    ManualPlaybookReplayService(
            TroubleshootingSopPersistenceService candidates,
            ManualPlaybookReplaySuiteCatalog catalog,
            ManualPlaybookReplayFingerprint fingerprints,
            ManualPlaybookReplayEvaluator evaluator,
            ManualPlaybookReplayAttestationStore store,
            Clock clock,
            Supplier<String> attestationIds) {
        this.candidates = Objects.requireNonNull(candidates, "candidates");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.fingerprints = Objects.requireNonNull(fingerprints, "fingerprints");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.attestationIds = Objects.requireNonNull(attestationIds, "attestationIds");
    }

    public ManualPlaybookReplayAttestation run(
            long workspaceId,
            String sourceRecordId,
            String actor) {
        validateWorkspace(workspaceId);
        String sourceId = safe(sourceRecordId, "sourceRecordId", MAX_SOURCE_ID);
        String executedBy = safe(actor, "actor", MAX_ACTOR);
        SopEntry candidate = candidates.findBySopId(workspaceId, sourceId);
        if (candidate == null) {
            throw new MateClawException(
                    "err.troubleshooting.sop_not_found",
                    404,
                    "manual Playbook candidate does not exist in this workspace");
        }
        if (!sourceId.equals(candidate.sopId())) {
            throw conflict("manual replay candidate identity does not match the request");
        }
        if (!"candidate".equals(candidate.status()) || candidate.verified()) {
            throw conflict("manual replay requires an immutable unverified candidate");
        }
        ManualPlaybookReplaySuiteCatalog.ResolvedSuite resolved = catalog.find(
                        candidate.routingKey())
                .orElseThrow(() -> conflict(
                        "no server-owned replay suite is registered for "
                                + candidate.routingKey()));
        String candidateFingerprint = fingerprints.candidate(candidate);
        Optional<ManualPlaybookReplayAttestation> existing = store.find(
                workspaceId,
                candidate.sopId(),
                candidateFingerprint,
                resolved.fingerprint());
        if (existing.isPresent()) {
            return existing.get();
        }

        ManualPlaybookReplayEvaluation evaluation = evaluator.evaluate(
                candidate, resolved.suite());
        Instant executedAt = Instant.now(clock);
        ManualPlaybookReplayAttestation attestation =
                new ManualPlaybookReplayAttestation(
                        safe(attestationIds.get(), "attestationId", 128),
                        candidate.sopId(),
                        candidate.routingKey(),
                        candidateFingerprint,
                        resolved.suite().suiteId(),
                        resolved.suite().suiteVersion(),
                        resolved.fingerprint(),
                        evaluation.passed()
                                ? ManualPlaybookReplayAttestation.Status.PASSED
                                : ManualPlaybookReplayAttestation.Status.FAILED,
                        evaluation.positiveTotal(),
                        evaluation.positivePassed(),
                        evaluation.negativeOrAbstainTotal(),
                        evaluation.negativeOrAbstainPassed(),
                        evaluation.failureCodes(),
                        true,
                        executedBy,
                        executedAt);
        return store.saveOrGet(workspaceId, attestation).attestation();
    }

    /** Returns a bundled import example without exposing its replay cases. */
    public SopEntry exampleCandidate(String selectorKey) {
        String selector = safe(selectorKey, "selectorKey", 256);
        return catalog.find(selector)
                .map(ManualPlaybookReplaySuiteCatalog.ResolvedSuite::suite)
                .map(ManualPlaybookReplaySuite::exampleCandidate)
                .orElseThrow(() -> new MateClawException(
                        "err.troubleshooting.manual_replay_suite_not_found",
                        404,
                        "no manual Playbook example is registered for " + selector));
    }

    /** Computes current identities and returns only an exact persisted match. */
    public ManualPlaybookReplayQualification qualification(
            long workspaceId,
            SopEntry candidate) {
        validateWorkspace(workspaceId);
        if (candidate == null) {
            throw new IllegalArgumentException("candidate is required");
        }
        String candidateFingerprint = fingerprints.candidate(candidate);
        Optional<ManualPlaybookReplaySuiteCatalog.ResolvedSuite> resolved =
                catalog.find(candidate.routingKey());
        if (resolved.isEmpty()) {
            return new ManualPlaybookReplayQualification(
                    candidateFingerprint, null, null);
        }
        String suiteFingerprint = resolved.get().fingerprint();
        return new ManualPlaybookReplayQualification(
                candidateFingerprint,
                suiteFingerprint,
                store.find(
                                workspaceId,
                                candidate.sopId(),
                                candidateFingerprint,
                                suiteFingerprint)
                        .orElse(null));
    }

    private void validateWorkspace(long workspaceId) {
        if (workspaceId <= 0) {
            throw new MateClawException(
                    "err.troubleshooting.workspace_required",
                    400,
                    "workspaceId must be positive");
        }
    }

    private String safe(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()
                || normalized.length() > maxLength
                || normalized.chars().anyMatch(Character::isISOControl)
                || !TroubleshootingSecretRedactor.redact(normalized).equals(normalized)) {
            throw new MateClawException(
                    "err.troubleshooting.manual_replay_invalid",
                    400,
                    field + " must contain safe bounded text");
        }
        return normalized;
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.manual_replay_conflict", 409, message);
    }
}
