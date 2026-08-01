package vip.mate.troubleshooting.synthesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable classpath catalog; browsers cannot provide replay cases or expectations. */
@Component
public final class ManualPlaybookReplaySuiteCatalog {

    private static final String RESOURCE =
            "troubleshooting/replay/manual-playbook-replay-suites.json";

    private final Map<String, ResolvedSuite> suites;

    @Autowired
    public ManualPlaybookReplaySuiteCatalog(
            ObjectMapper objectMapper,
            ManualPlaybookReplayFingerprint fingerprints,
            ManualPlaybookReplayEvaluator evaluator) {
        this(
                objectMapper,
                fingerprints,
                evaluator,
                new ClassPathResource(RESOURCE));
    }

    ManualPlaybookReplaySuiteCatalog(
            ObjectMapper objectMapper,
            ManualPlaybookReplayFingerprint fingerprints,
            ManualPlaybookReplayEvaluator evaluator,
            Resource resource) {
        if (objectMapper == null
                || fingerprints == null
                || evaluator == null
                || resource == null) {
            throw new IllegalArgumentException(
                    "objectMapper, fingerprints, evaluator and replay resource are required");
        }
        try (InputStream input = resource.getInputStream()) {
            CatalogDocument document = objectMapper.readValue(
                    input, CatalogDocument.class);
            if (document.version() != 1 || document.suites().isEmpty()) {
                throw new IllegalArgumentException("unsupported or empty manual replay catalog");
            }
            Map<String, ResolvedSuite> loaded = new LinkedHashMap<>();
            for (ManualPlaybookReplaySuite suite : document.suites()) {
                if (suite.exampleCandidate() == null
                        || !evaluator.evaluate(
                                suite.exampleCandidate(), suite).passed()) {
                    throw new IllegalArgumentException(
                            "manual replay suite example must pass its own cases");
                }
                ResolvedSuite resolved = new ResolvedSuite(
                        suite, fingerprints.suite(suite));
                if (loaded.putIfAbsent(suite.selectorKey(), resolved) != null) {
                    throw new IllegalArgumentException(
                            "manual replay selectors must be unique");
                }
            }
            suites = Map.copyOf(loaded);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "bundled manual Playbook replay catalog is invalid", failure);
        }
    }

    public Optional<ResolvedSuite> find(String selectorKey) {
        if (selectorKey == null || selectorKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(suites.get(selectorKey.trim()));
    }

    public record ResolvedSuite(
            ManualPlaybookReplaySuite suite,
            String fingerprint) {

        public ResolvedSuite {
            if (suite == null
                    || fingerprint == null
                    || !fingerprint.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException(
                        "resolved replay suite and SHA-256 fingerprint are required");
            }
        }
    }

    public record CatalogDocument(int version, List<ManualPlaybookReplaySuite> suites) {
        public CatalogDocument {
            suites = List.copyOf(suites == null ? List.of() : suites);
        }
    }
}
