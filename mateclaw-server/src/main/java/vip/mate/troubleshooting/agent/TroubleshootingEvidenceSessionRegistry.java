package vip.mate.troubleshooting.agent;

import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.IncidentContext;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Server-side capability session for the troubleshooting evidence tool.
 *
 * <p>The LLM receives only an opaque conversation id. The incident and source
 * routing context live here, so tool arguments cannot spoof another workspace,
 * system, or incident.</p>
 */
@Component
public final class TroubleshootingEvidenceSessionRegistry {

    private static final Pattern SAFE_EVIDENCE_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private final EvidenceSourceRouter router;
    private final TroubleshootingAgentProperties properties;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    public TroubleshootingEvidenceSessionRegistry(
            EvidenceSourceRouter router,
            TroubleshootingAgentProperties properties) {
        this.router = router;
        this.properties = properties;
    }

    public SessionHandle open(
            String conversationId,
            long workspaceId,
            IncidentContext incident,
            List<EvidenceResult> suppliedEvidence) {
        String id = required(conversationId, "conversationId");
        if (workspaceId <= 0) {
            throw new IllegalArgumentException("workspaceId must be positive");
        }
        if (incident == null) {
            throw new IllegalArgumentException("incident is required");
        }
        SessionState state = new SessionState(workspaceId, incident, suppliedEvidence);
        if (sessions.putIfAbsent(id, state) != null) {
            throw new IllegalStateException("troubleshooting session already exists: " + id);
        }
        return new SessionHandle(id, state);
    }

    public EvidenceResult collect(
            String conversationId,
            long workspaceId,
            EvidenceRequest request) {
        SessionState state = sessions.get(required(conversationId, "conversationId"));
        if (state == null) {
            throw new IllegalStateException("no active troubleshooting session");
        }
        if (workspaceId <= 0 || state.workspaceId != workspaceId) {
            throw new IllegalStateException("troubleshooting session workspace mismatch");
        }
        if (request == null) {
            throw new IllegalArgumentException("evidence request is required");
        }
        if (!isSafeEvidenceId(request.requestId())) {
            throw new IllegalArgumentException("evidence requestId must be a safe identifier");
        }
        synchronized (state) {
            if (state.evidence.containsKey(request.requestId())) {
                throw new IllegalArgumentException(
                        "evidence requestId must be unique within a triage session");
            }
            int limit = properties.getMaxEvidenceRequests();
            if (limit <= 0 || state.requestCount >= limit) {
                throw new IllegalStateException("troubleshooting evidence request limit reached");
            }
            state.requestCount++;
            EvidenceResult collected = router.collect(request, state.incident);
            if (collected == null) {
                throw new IllegalStateException("evidence source returned no result");
            }
            EvidenceResult sanitized = TroubleshootingSecretRedactor.redact(collected);
            EvidenceResult result = withQueryId(sanitized, request.requestId());
            state.evidence.put(result.queryId(), result);
            state.toolCollectedQueryIds.add(result.queryId());
            return result;
        }
    }

    static boolean isSafeEvidenceId(String value) {
        if (value == null) {
            return false;
        }
        String candidate = value.trim();
        return SAFE_EVIDENCE_ID.matcher(candidate).matches()
                && candidate.equals(TroubleshootingSecretRedactor.redact(candidate));
    }

    private static EvidenceResult withQueryId(EvidenceResult result, String queryId) {
        return new EvidenceResult(
                queryId,
                result.namespace(),
                result.query(),
                result.status(),
                result.summary(),
                result.observed(),
                result.source(),
                result.collectedAt());
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static final class SessionState {
        private final long workspaceId;
        private final IncidentContext incident;
        private final LinkedHashMap<String, EvidenceResult> evidence = new LinkedHashMap<>();
        private final LinkedHashSet<String> toolCollectedQueryIds = new LinkedHashSet<>();
        private int requestCount;

        private SessionState(
                long workspaceId,
                IncidentContext incident,
                List<EvidenceResult> suppliedEvidence) {
            this.workspaceId = workspaceId;
            this.incident = incident;
            int redactedIndex = 1;
            for (EvidenceResult result : suppliedEvidence == null
                    ? List.<EvidenceResult>of() : suppliedEvidence) {
                if (result == null) {
                    throw new IllegalArgumentException("supplied evidence must not contain null");
                }
                EvidenceResult sanitized = TroubleshootingSecretRedactor.redact(result);
                String queryId = sanitized.queryId();
                if (!isSafeEvidenceId(queryId)) {
                    do {
                        queryId = "supplied-redacted-" + redactedIndex++;
                    } while (evidence.containsKey(queryId));
                    sanitized = withQueryId(sanitized, queryId);
                }
                if (evidence.putIfAbsent(queryId, sanitized) != null) {
                    throw new IllegalArgumentException(
                            "duplicate evidence queryId: " + queryId);
                }
            }
        }

        private SessionSnapshot snapshot() {
            synchronized (this) {
                return new SessionSnapshot(
                        List.copyOf(evidence.values()),
                        Set.copyOf(toolCollectedQueryIds));
            }
        }
    }

    public record SessionSnapshot(
            List<EvidenceResult> evidence,
            Set<String> toolCollectedQueryIds) {
    }

    public final class SessionHandle implements AutoCloseable {
        private final String conversationId;
        private final SessionState state;
        private final AtomicBoolean closed = new AtomicBoolean();

        private SessionHandle(String conversationId, SessionState state) {
            this.conversationId = conversationId;
            this.state = state;
        }

        public SessionSnapshot snapshot() {
            return state.snapshot();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                sessions.remove(conversationId, state);
            }
        }
    }
}
