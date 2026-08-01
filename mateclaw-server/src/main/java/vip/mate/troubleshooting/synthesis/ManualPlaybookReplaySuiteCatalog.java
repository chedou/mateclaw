package vip.mate.troubleshooting.synthesis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Immutable classpath catalog; browsers cannot provide replay cases or expectations. */
@Component
public final class ManualPlaybookReplaySuiteCatalog {

    private static final Logger log =
            LoggerFactory.getLogger(ManualPlaybookReplaySuiteCatalog.class);

    private static final String RESOURCE =
            "troubleshooting/replay/manual-playbook-replay-suites.json";

    private final Map<String, ResolvedSuite> suites;
    private final List<RejectedSeed> rejectedSeeds;

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
            JsonNode document = objectMapper.readTree(input);
            int version = document.path("version").asInt(-1);
            JsonNode fixedSuites = document.path("suites");
            if ((version != 1 && version != 2)
                    || !fixedSuites.isArray()
                    || fixedSuites.isEmpty()) {
                throw new IllegalArgumentException("unsupported or empty manual replay catalog");
            }
            Map<String, ResolvedSuite> loaded = new LinkedHashMap<>();
            for (JsonNode node : fixedSuites) {
                ManualPlaybookReplaySuite suite = objectMapper.treeToValue(
                        node, ManualPlaybookReplaySuite.class);
                addFixed(loaded, resolve(suite, fingerprints, evaluator));
            }

            List<RejectedSeed> rejected = new ArrayList<>();
            if (version == 2) {
                JsonNode seeds = document.path("recordedEvidenceSeeds");
                if (!seeds.isMissingNode() && !seeds.isArray()) {
                    throw new IllegalArgumentException(
                            "recordedEvidenceSeeds must be an array");
                }
                ManualPlaybookReplaySuiteTemplateFactory templateFactory =
                        new ManualPlaybookReplaySuiteTemplateFactory();
                int index = 0;
                for (JsonNode node : seeds) {
                    String reference = seedReference(node, index++);
                    try {
                        ManualPlaybookRecordedEvidenceSeed seed = objectMapper.treeToValue(
                                node, ManualPlaybookRecordedEvidenceSeed.class);
                        ManualPlaybookReplaySuite generated =
                                templateFactory.generate(seed);
                        ResolvedSuite resolved = resolve(
                                generated, fingerprints, evaluator);
                        if (loaded.putIfAbsent(generated.selectorKey(), resolved) != null) {
                            throw new IllegalArgumentException(
                                    "manual replay selectors must be unique");
                        }
                    } catch (Exception failure) {
                        RejectedSeed item = new RejectedSeed(
                                reference, "INVALID_RECORDED_EVIDENCE_SEED");
                        rejected.add(item);
                        log.warn("[manual-replay] quarantined recorded seed {} ({})",
                                item.reference(), item.code());
                    }
                }
            }
            suites = Map.copyOf(loaded);
            rejectedSeeds = List.copyOf(rejected);
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

    public List<RejectedSeed> rejectedSeeds() {
        return rejectedSeeds;
    }

    private ResolvedSuite resolve(
            ManualPlaybookReplaySuite suite,
            ManualPlaybookReplayFingerprint fingerprints,
            ManualPlaybookReplayEvaluator evaluator) {
        if (suite.exampleCandidate() == null
                || !evaluator.evaluate(suite.exampleCandidate(), suite).passed()) {
            throw new IllegalArgumentException(
                    "manual replay suite example must pass its own cases");
        }
        return new ResolvedSuite(suite, fingerprints.suite(suite));
    }

    private void addFixed(
            Map<String, ResolvedSuite> loaded,
            ResolvedSuite resolved) {
        if (loaded.putIfAbsent(resolved.suite().selectorKey(), resolved) != null) {
            throw new IllegalArgumentException("manual replay selectors must be unique");
        }
    }

    private String seedReference(JsonNode node, int index) {
        String selector = node.path("selectorKey").asText("").trim();
        return selector.isEmpty() ? "recordedEvidenceSeeds[" + index + "]" : selector;
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

    public record RejectedSeed(String reference, String code) {
        public RejectedSeed {
            if (reference == null || reference.isBlank()
                    || code == null || !code.matches("[A-Z0-9_]+")) {
                throw new IllegalArgumentException(
                        "recorded seed rejection requires a bounded reference and code");
            }
            reference = reference.trim();
        }
    }
}
