package vip.mate.troubleshooting.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.TroubleshootingEvidenceSanitizer;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.evidence.EvidenceSpineOrchestrator;
import vip.mate.troubleshooting.evidence.EvidenceSpinePlan;
import vip.mate.troubleshooting.evidence.EvidenceSpineResult;
import vip.mate.troubleshooting.evidence.EvidenceSpineStage;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.synthesis.DeterministicLogTraceCompressor;
import vip.mate.troubleshooting.synthesis.LogTraceSkeleton;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server-side capability session for the troubleshooting evidence tool.
 *
 * <p>The LLM receives only an opaque conversation id. The incident and source
 * routing context live here, so tool arguments cannot spoof another workspace,
 * system, or incident.</p>
 */
@Component
public final class TroubleshootingEvidenceSessionRegistry {

    public static final String ONLINE_SEARCH_REQUEST_ID =
            EvidenceSpineStage.SEARCH.onlineRequestId();
    public static final String ONLINE_TRACE_REQUEST_ID =
            EvidenceSpineStage.TRACE.onlineRequestId();
    public static final String ONLINE_CONTRAST_REQUEST_ID =
            EvidenceSpineStage.CONTRAST.onlineRequestId();

    private final EvidenceSpineOrchestrator evidenceOrchestration;
    private final TroubleshootingAgentProperties properties;
    private final ApprovedEvidenceSpineCatalog approvedPlans;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    @Autowired
    public TroubleshootingEvidenceSessionRegistry(
            EvidenceSpineOrchestrator evidenceOrchestration,
            TroubleshootingAgentProperties properties,
            ApprovedEvidenceSpineCatalog approvedPlans) {
        this.evidenceOrchestration = evidenceOrchestration;
        this.properties = properties;
        this.approvedPlans = approvedPlans;
    }

    /** Test/compatibility constructor; runtime injection uses the shared orchestrator bean. */
    public TroubleshootingEvidenceSessionRegistry(
            EvidenceSourceRouter router,
            TroubleshootingAgentProperties properties) {
        this(new EvidenceSpineOrchestrator(
                router, new DeterministicLogTraceCompressor()), properties,
                new ApprovedEvidenceSpineCatalog(properties));
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

    public ToolCollection collectForTool(
            String conversationId,
            long workspaceId,
            EvidenceRequest request) {
        SessionState state = requireSession(conversationId, workspaceId);
        synchronized (state) {
            try {
                validateRequest(request);
                String signalKind = request.signalKind().trim().toLowerCase(Locale.ROOT);
                if (!"log_search".equals(signalKind)) {
                    throw new IllegalArgumentException(
                            "online Agent may select only an approved scenario evidence plan");
                }
                return collectSpine(state, workspaceId, request);
            } catch (RuntimeException rejected) {
                state.markCoreFailure("online evidence plan request was rejected");
                throw rejected;
            }
        }
    }

    public List<String> approvedScenarioKeys(long workspaceId, IncidentContext incident) {
        return approvedPlans.visibleScenarioKeys(workspaceId, incident);
    }

    public void recordToolRejection(String conversationId, long workspaceId) {
        SessionState state = requireSession(conversationId, workspaceId);
        synchronized (state) {
            state.markCoreFailure("online evidence tool request was rejected");
        }
    }

    private ToolCollection collectSpine(
            SessionState state,
            long workspaceId,
            EvidenceRequest request) {
        requireAvailableIds(
                state,
                Set.of(
                        ONLINE_SEARCH_REQUEST_ID,
                        ONLINE_TRACE_REQUEST_ID,
                        ONLINE_CONTRAST_REQUEST_ID));
        requireCapacity(state, 3);
        if (!request.target().keySet().equals(Set.of("scenario_key"))) {
            throw new IllegalArgumentException(
                    "log_search target must contain only a registered scenario_key");
        }
        if (request.window() != null) {
            throw new IllegalArgumentException(
                    "online evidence window is owned by the approved server plan");
        }
        Object rawScenarioKey = request.target().get("scenario_key");
        if (!(rawScenarioKey instanceof String scenarioKey)) {
            throw new IllegalArgumentException("scenario_key must be a string");
        }
        ApprovedEvidenceSpineCatalog.ApprovedSpinePlan approved = approvedPlans.resolve(
                workspaceId, state.incident, scenarioKey);
        EvidenceSpinePlan plan = approved.evidencePlan();
        EvidenceSpineResult spine = evidenceOrchestration.collect(
                workspaceId, state.incident, plan, approved.permittedPlatforms());
        state.requestCount += spine.sourceRequestCount();
        state.markCoreFailure(spine.coreFailure());
        for (EvidenceResult result : spine.evidence()) {
            EvidenceResult sanitized = TroubleshootingSecretRedactor.redact(result);
            state.evidence.put(sanitized.queryId(), sanitized);
            state.toolCollectedQueryIds.add(sanitized.queryId());
        }
        return new ToolCollection(
                state.evidence.get(ONLINE_SEARCH_REQUEST_ID),
                spine.evidence().stream()
                        .map(result -> state.evidence.get(result.queryId()))
                        .toList(),
                spine.skeleton(),
                spine.coreFailure());
    }

    private void requireAvailableIds(SessionState state, Set<String> requestIds) {
        if (requestIds.stream().anyMatch(state.evidence::containsKey)) {
            throw new IllegalArgumentException(
                    "evidence requestId must be unique within a triage session");
        }
    }

    private void requireCapacity(SessionState state, int requested) {
        int limit = properties.getMaxEvidenceRequests();
        if (limit <= 0 || requested <= 0 || state.requestCount > limit - requested) {
            throw new IllegalStateException("troubleshooting evidence request limit reached");
        }
    }

    private SessionState requireSession(String conversationId, long workspaceId) {
        SessionState state = sessions.get(required(conversationId, "conversationId"));
        if (state == null) {
            throw new IllegalStateException("no active troubleshooting session");
        }
        if (workspaceId <= 0 || state.workspaceId != workspaceId) {
            throw new IllegalStateException("troubleshooting session workspace mismatch");
        }
        return state;
    }

    private void validateRequest(EvidenceRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("evidence request is required");
        }
        if (!isSafeEvidenceId(request.requestId())) {
            throw new IllegalArgumentException("evidence requestId must be a safe identifier");
        }
    }

    static boolean isSafeEvidenceId(String value) {
        return TroubleshootingEvidenceSanitizer.isSafeEvidenceId(value);
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
        private String coreEvidenceFailure;

        private SessionState(
                long workspaceId,
                IncidentContext incident,
                List<EvidenceResult> suppliedEvidence) {
            this.workspaceId = workspaceId;
            this.incident = incident;
            for (EvidenceResult sanitized
                    : TroubleshootingEvidenceSanitizer.sanitizeSupplied(suppliedEvidence)) {
                String queryId = sanitized.queryId();
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
                        Set.copyOf(toolCollectedQueryIds),
                        coreEvidenceFailure);
            }
        }

        private void markCoreFailure(String reason) {
            if (coreEvidenceFailure == null && reason != null && !reason.isBlank()) {
                coreEvidenceFailure = reason.trim();
            }
        }
    }

    public record SessionSnapshot(
            List<EvidenceResult> evidence,
            Set<String> toolCollectedQueryIds,
            String coreEvidenceFailure) {
    }

    public record ToolCollection(
            EvidenceResult requestedEvidence,
            List<EvidenceResult> evidence,
            LogTraceSkeleton traceSkeleton,
            String coreFailure) {

        public ToolCollection {
            if (requestedEvidence == null) {
                throw new IllegalArgumentException("requested evidence is required");
            }
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
            coreFailure = coreFailure == null || coreFailure.isBlank()
                    ? null
                    : coreFailure.trim();
        }
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
