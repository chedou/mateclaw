package vip.mate.troubleshooting.investigation;

import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Domain-local semantic evidence capability.
 *
 * <p>This SPI deliberately has no write method. Implementations may only obtain
 * evidence and must return the canonical {@link EvidenceResult} vocabulary.</p>
 */
public interface ReadOnlyEvidenceTool {

    Descriptor descriptor();

    EvidenceResult collect(ReadOnlyToolRegistry.Context context, EvidenceRequest request);

    enum Capability {
        READ_EVIDENCE
    }

    record Descriptor(
            String toolKey,
            String version,
            Capability capability,
            Set<String> signalKinds) {

        public Descriptor {
            toolKey = required(toolKey, "toolKey").toLowerCase(Locale.ROOT);
            version = required(version, "version");
            if (capability != Capability.READ_EVIDENCE) {
                throw new IllegalArgumentException("read-only evidence tool capability is required");
            }
            LinkedHashSet<String> normalizedSignals = new LinkedHashSet<>();
            for (String signalKind : signalKinds == null ? Set.<String>of() : signalKinds) {
                normalizedSignals.add(required(signalKind, "signalKind").toLowerCase(Locale.ROOT));
            }
            if (normalizedSignals.isEmpty()) {
                throw new IllegalArgumentException("signalKinds must not be empty");
            }
            signalKinds = Set.copyOf(normalizedSignals);
        }

        public String identity() {
            return toolKey + "@" + version;
        }

        public boolean supports(String signalKind) {
            return signalKind != null
                    && signalKinds.contains(signalKind.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
