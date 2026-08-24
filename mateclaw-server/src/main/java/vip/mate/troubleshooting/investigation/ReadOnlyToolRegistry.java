package vip.mate.troubleshooting.investigation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.evidence.CanonicalEvidenceSchema;
import vip.mate.troubleshooting.evidence.FormalEvidenceAuthorityException;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentContext;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Registry for server-owned, read-only troubleshooting tools.
 *
 * <p>The planner chooses only an approved semantic identity. It cannot choose
 * an implementation class, platform query, endpoint, or credential.</p>
 */
@Component
public final class ReadOnlyToolRegistry {

    private final Map<String, ReadOnlyEvidenceTool> tools;
    private final Clock clock;

    @Autowired
    public ReadOnlyToolRegistry(List<ReadOnlyEvidenceTool> tools) {
        this(tools, Clock.systemUTC());
    }

    public ReadOnlyToolRegistry(List<ReadOnlyEvidenceTool> tools, Clock clock) {
        Map<String, ReadOnlyEvidenceTool> indexed = new LinkedHashMap<>();
        for (ReadOnlyEvidenceTool tool : tools == null
                ? List.<ReadOnlyEvidenceTool>of() : tools) {
            if (tool == null || tool.descriptor() == null) {
                throw new IllegalArgumentException("read-only evidence tool descriptor is required");
            }
            String identity = normalizeIdentity(tool.descriptor().identity());
            if (indexed.putIfAbsent(identity, tool) != null) {
                throw new IllegalArgumentException(
                        "duplicate read-only evidence tool: " + identity);
            }
        }
        this.tools = Map.copyOf(indexed);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public EvidenceResult collect(Invocation invocation) {
        if (invocation == null) {
            throw new IllegalArgumentException("invocation is required");
        }
        String identity = normalizeIdentity(invocation.toolKey() + "@" + invocation.version());
        if (!invocation.allowedToolIdentities().contains(identity)) {
            throw new PolicyViolation("read-only evidence tool is not allowed: " + identity);
        }
        if (!invocation.allowedSignalKinds().contains(
                normalizeSignal(invocation.request().signalKind()))) {
            throw new PolicyViolation(
                    "signal is not allowed by the frozen plan: "
                            + invocation.request().signalKind());
        }
        ReadOnlyEvidenceTool tool = tools.get(identity);
        if (tool == null) {
            throw new PolicyViolation("read-only evidence tool is not registered: " + identity);
        }
        if (!tool.descriptor().supports(invocation.request().signalKind())) {
            throw new PolicyViolation("read-only evidence tool " + identity
                    + " does not support signal " + invocation.request().signalKind());
        }
        if (!clock.instant().isBefore(invocation.deadline())) {
            throw new PolicyViolation("read-only evidence deadline is exhausted");
        }

        EvidenceResult result;
        try {
            result = tool.collect(
                    new Context(
                            invocation.workspaceId(),
                            invocation.incident(),
                            invocation.permittedPlatforms(),
                            invocation.deadline(),
                            invocation.sourceBindingFingerprint()),
                    invocation.request());
        } catch (FormalEvidenceAuthorityException authorityFailure) {
            throw authorityFailure;
        } catch (RuntimeException unavailable) {
            return missing(invocation.request(), "tool-registry:unavailable");
        }
        if (!validCanonical(invocation.request(), result)) {
            return missing(invocation.request(), "tool-registry:invalid-canonical-output");
        }
        return result;
    }

    public List<ReadOnlyEvidenceTool.Descriptor> descriptors() {
        return tools.values().stream()
                .map(ReadOnlyEvidenceTool::descriptor)
                .sorted(java.util.Comparator.comparing(ReadOnlyEvidenceTool.Descriptor::identity))
                .toList();
    }

    private boolean validCanonical(EvidenceRequest request, EvidenceResult result) {
        if (result == null || !request.requestId().equals(result.queryId())) {
            return false;
        }
        if (result.status() == EvidenceStatus.MISSING) {
            return result.observed().isEmpty();
        }
        return CanonicalEvidenceSchema.isValid(request.signalKind(), result.observed());
    }

    private EvidenceResult missing(EvidenceRequest request, String source) {
        return new EvidenceResult(
                request.requestId(),
                request.signalKind(),
                "",
                EvidenceStatus.MISSING,
                "no canonical evidence was returned",
                Map.of(),
                source,
                clock.instant());
    }

    private static String normalizeIdentity(String value) {
        return required(value, "tool identity").toLowerCase(Locale.ROOT);
    }

    private static String normalizeSignal(String value) {
        return required(value, "signal kind").toLowerCase(Locale.ROOT);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    public record Invocation(
            String toolKey,
            String version,
            long workspaceId,
            IncidentContext incident,
            EvidenceRequest request,
            Set<String> allowedToolIdentities,
            Set<String> allowedSignalKinds,
            Set<String> permittedPlatforms,
            Instant deadline,
            String sourceBindingFingerprint) {

        public Invocation(
                String toolKey,
                String version,
                long workspaceId,
                IncidentContext incident,
                EvidenceRequest request,
                Set<String> allowedToolIdentities,
                Set<String> allowedSignalKinds,
                Set<String> permittedPlatforms,
                Instant deadline) {
            this(
                    toolKey,
                    version,
                    workspaceId,
                    incident,
                    request,
                    allowedToolIdentities,
                    allowedSignalKinds,
                    permittedPlatforms,
                    deadline,
                    null);
        }

        public Invocation {
            toolKey = required(toolKey, "toolKey");
            version = required(version, "version");
            if (workspaceId <= 0 || incident == null || request == null || deadline == null) {
                throw new IllegalArgumentException(
                        "workspaceId, incident, request and deadline are required");
            }
            LinkedHashSet<String> allowed = new LinkedHashSet<>();
            for (String identity : allowedToolIdentities == null
                    ? Set.<String>of() : allowedToolIdentities) {
                allowed.add(normalizeIdentity(identity));
            }
            allowedToolIdentities = Set.copyOf(allowed);
            allowedSignalKinds = normalizeSet(allowedSignalKinds);
            permittedPlatforms = normalizeSet(permittedPlatforms);
            sourceBindingFingerprint = optionalFingerprint(
                    sourceBindingFingerprint);
        }
    }

    public record Context(
            long workspaceId,
            IncidentContext incident,
            Set<String> permittedPlatforms,
            Instant deadline,
            String sourceBindingFingerprint) {

        public Context(
                long workspaceId,
                IncidentContext incident,
                Set<String> permittedPlatforms,
                Instant deadline) {
            this(workspaceId, incident, permittedPlatforms, deadline, null);
        }

        public Context {
            if (workspaceId <= 0 || incident == null || deadline == null) {
                throw new IllegalArgumentException(
                        "workspaceId, incident and deadline are required");
            }
            permittedPlatforms = normalizeSet(permittedPlatforms);
            sourceBindingFingerprint = optionalFingerprint(
                    sourceBindingFingerprint);
        }
    }

    private static String optionalFingerprint(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "source binding fingerprint must be SHA-256 hex");
        }
        return normalized;
    }

    private static Set<String> normalizeSet(Set<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values == null ? Set.<String>of() : values) {
            normalized.add(required(value, "set item").toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(normalized);
    }

    public static final class PolicyViolation extends IllegalStateException {
        public PolicyViolation(String message) {
            super(message);
        }
    }
}
