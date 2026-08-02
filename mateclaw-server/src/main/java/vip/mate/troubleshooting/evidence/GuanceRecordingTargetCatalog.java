package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.service.KnowledgeEvidenceSelectorInventory;
import vip.mate.troubleshooting.synthesis.ManualPlaybookContractValidator;
import vip.mate.troubleshooting.synthesis.ManualPlaybookReplayFingerprint;
import vip.mate.troubleshooting.synthesis.ManualPlaybookReplaySuiteCatalog;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable, secret-free catalog of new D19 recording targets.
 *
 * <p>An operator plan may choose a target and add a historical timestamp. It
 * may not invent the selector, candidate, evidence request, lookup key, window
 * or Guance binding. The candidate and request contracts originate in this
 * classpath catalog; their public identities are recomputed by the server.
 * The bundled catalog intentionally starts empty: the already recorded
 * SendMsg target is not a new seed, and no other Guance query contract has
 * been verified yet.</p>
 */
@Component
public final class GuanceRecordingTargetCatalog {

    static final String CONTRACT_VERSION =
            "t7-guance-recording-target-catalog.v1";
    static final String RESOURCE =
            "troubleshooting/evidence/guance-recording-targets.json";

    private static final int MAX_RESOURCE_BYTES = 128 * 1024;
    private static final int MAX_TARGETS = 146;
    private static final List<String> CORE_SIGNALS = List.of(
            "log_search", "log_trace_bundle", "contrast_sample");
    private static final Pattern SAFE_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
    private static final Pattern SAFE_REFERENCE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,255}");
    private static final Pattern SELECTOR =
            Pattern.compile("csdp:[A-Za-z0-9_]+");
    private static final Pattern WINDOW =
            Pattern.compile("-([1-9][0-9]{0,5})(s|m|h|d)");

    private final List<Target> targets;
    private final String catalogFingerprint;
    private final Clock clock;
    private final ObjectMapper strictMapper;
    private final ManualPlaybookReplayFingerprint fingerprints;

    @Autowired
    public GuanceRecordingTargetCatalog(
            ObjectMapper objectMapper,
            KnowledgeEvidenceSelectorInventory selectorInventory,
            ManualPlaybookReplaySuiteCatalog replayCatalog,
            ManualPlaybookReplayFingerprint fingerprints) {
        this(
                objectMapper,
                new ClassPathResource(RESOURCE),
                selectorInventory::contains,
                selector -> replayCatalog.find(selector)
                        .map(ManualPlaybookReplaySuiteCatalog.ResolvedSuite::evidenceGrade)
                        .filter(KnowledgeEvidenceGrade.RECORDED_AGGREGATE::equals)
                        .isPresent(),
                Clock.systemUTC(),
                fingerprints);
    }

    GuanceRecordingTargetCatalog(
            ObjectMapper objectMapper,
            Resource resource,
            Predicate<String> selectorKnown,
            Predicate<String> alreadyRecorded,
            Clock clock) {
        this(
                objectMapper,
                resource,
                selectorKnown,
                alreadyRecorded,
                clock,
                objectMapper == null
                        ? null
                        : new ManualPlaybookReplayFingerprint(objectMapper));
    }

    GuanceRecordingTargetCatalog(
            ObjectMapper objectMapper,
            Resource resource,
            Predicate<String> selectorKnown,
            Predicate<String> alreadyRecorded,
            Clock clock,
            ManualPlaybookReplayFingerprint fingerprints) {
        if (objectMapper == null
                || resource == null
                || selectorKnown == null
                || alreadyRecorded == null
                || fingerprints == null) {
            throw new IllegalArgumentException(
                    "objectMapper, resource, selector predicates and fingerprints are required");
        }
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.strictMapper = objectMapper.copy()
                .configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true)
                .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        this.fingerprints = fingerprints;
        try (InputStream input = resource.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_RESOURCE_BYTES + 1);
            if (bytes.length > MAX_RESOURCE_BYTES) {
                throw new IllegalArgumentException(
                        "Guance recording target catalog exceeds 128 KiB");
            }
            JsonNode document = strictMapper.readTree(bytes);
            targets = validate(document, selectorKnown, alreadyRecorded);
            catalogFingerprint = sha256(bytes);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "bundled Guance recording target catalog is invalid", failure);
        }
    }

    /**
     * Projects only targets whose exact binding identifiers match this running
     * service. DQL and credentials never leave the server.
     */
    public View inspect(GuanceEvidenceReadiness readiness) {
        if (readiness == null) {
            throw new IllegalArgumentException("Guance readiness is required");
        }
        Map<String, String> activeBindings = new LinkedHashMap<>();
        for (GuanceEvidenceReadiness.SignalReadiness signal : readiness.signals()) {
            if (signal.routedToGuance()
                    && readyOrObserved(signal.status())
                    && CORE_SIGNALS.contains(signal.signalKind())) {
                activeBindings.put(signal.signalKind(), signal.bindingRef());
            }
        }

        List<Target> scoped = targets.stream()
                .filter(target -> normalize(target.system())
                        .equals(normalize(readiness.system())))
                .filter(target -> normalize(target.service())
                        .equals(normalize(readiness.service())))
                .toList();
        List<Target> executable = scoped.stream()
                .filter(target -> target.bindingRefs().equals(activeBindings))
                .toList();

        List<String> blockers = new ArrayList<>();
        if (scoped.size() < 20) {
            blockers.add("only " + scoped.size()
                    + " server-frozen unrecorded targets exist for this scope; 20 required");
        }
        if (executable.size() < scoped.size()) {
            blockers.add((scoped.size() - executable.size())
                    + " frozen targets do not match the running signal bindings");
        }
        return new View(
                CONTRACT_VERSION,
                readiness.system(),
                readiness.service(),
                catalogFingerprint,
                scoped.size(),
                executable.size(),
                executable,
                clock.instant().getEpochSecond(),
                blockers);
    }

    private List<Target> validate(
            JsonNode document,
            Predicate<String> selectorKnown,
            Predicate<String> alreadyRecorded) throws Exception {
        if (document == null
                || !document.isObject()
                || !exactKeys(document, Set.of("contractVersion", "targets"))
                || !CONTRACT_VERSION.equals(text(document, "contractVersion"))
                || !document.path("targets").isArray()
                || document.path("targets").size() > MAX_TARGETS) {
            throw invalid("recording target catalog root is invalid");
        }

        Set<String> targetIds = new LinkedHashSet<>();
        Set<String> selectors = new LinkedHashSet<>();
        Set<String> candidateFingerprints = new LinkedHashSet<>();
        Set<String> requestFingerprints = new LinkedHashSet<>();
        List<Target> loaded = new ArrayList<>();
        for (JsonNode node : document.path("targets")) {
            Set<String> allowed = Set.of(
                    "targetId",
                    "candidateReference",
                    "requiredEvidenceRequestId",
                    "bindingRefs",
                    "candidate");
            if (!node.isObject() || !exactKeys(node, allowed)) {
                throw invalid("recording target fields are invalid");
            }

            String targetId = safe(text(node, "targetId"), SAFE_ID, "targetId");
            String candidateReference = safe(
                    text(node, "candidateReference"),
                    SAFE_REFERENCE,
                    "candidateReference");
            String requestId = safe(
                    text(node, "requiredEvidenceRequestId"),
                    SAFE_ID,
                    "requiredEvidenceRequestId");
            Map<String, String> bindingRefs = bindingRefs(node.path("bindingRefs"));

            JsonNode candidateNode = node.path("candidate");
            if (!candidateNode.isObject()) {
                throw invalid("recording target candidate contract is required");
            }
            SopEntry candidate = strictMapper.treeToValue(candidateNode, SopEntry.class);
            validateSharedCandidateContract(candidate);
            String system = safe(candidate.system(), SAFE_ID, "candidate.system");
            String service = safe(candidate.service(), SAFE_ID, "candidate.service");
            String selectorKey = safe(
                    candidate.routingKey(), SELECTOR, "candidate.routingKey");
            EvidenceRequest request = selectedRequest(candidate, requestId);
            String searchTerm = selectedSearchTerm(request);
            String window = request.window();
            validateWindow(window);
            validateRequestFeedsDiagnosis(candidate, requestId);

            String candidateFingerprint = fingerprints.candidate(candidate);
            String requestFingerprint = fingerprints.evidenceRequest(request);

            if (!selectorKnown.test(selectorKey)) {
                throw invalid("recording target selector is outside frozen D1 inventory");
            }
            if (alreadyRecorded.test(selectorKey)) {
                throw invalid("recording target selector already has recorded authority");
            }
            if (!targetIds.add(targetId)
                    || !selectors.add(selectorKey)
                    || !candidateFingerprints.add(candidateFingerprint)
                    || !requestFingerprints.add(requestFingerprint)) {
                throw invalid(
                        "recording target, selector, candidate and request identities must be unique");
            }
            loaded.add(new Target(
                    targetId,
                    system,
                    service,
                    selectorKey,
                    candidateReference,
                    candidateFingerprint,
                    requestId,
                    requestFingerprint,
                    searchTerm,
                    window,
                    bindingRefs));
        }
        return List.copyOf(loaded);
    }

    private void validateSharedCandidateContract(SopEntry candidate) {
        var errors = ManualPlaybookContractValidator.validate(candidate);
        if (!errors.isEmpty()) {
            var first = errors.getFirst();
            throw invalid("recording target candidate violates shared manual Playbook contract: "
                    + first.code() + " at " + first.fieldPath());
        }
    }

    private EvidenceRequest selectedRequest(SopEntry candidate, String requestId) {
        List<EvidenceRequest> selected = candidate.evidenceRequests().stream()
                .filter(request -> requestId.equals(request.requestId()))
                .toList();
        if (selected.size() != 1) {
            throw invalid(
                    "required evidence request must exist exactly once in the candidate");
        }
        EvidenceRequest request = selected.getFirst();
        if (!request.required() || !"log_search".equals(request.signalKind())) {
            throw invalid("recording target request must be required log_search");
        }
        return request;
    }

    private String selectedSearchTerm(EvidenceRequest request) {
        if (!request.target().keySet().equals(Set.of("search_term"))) {
            throw invalid("recording target request must bind only search_term");
        }
        Object raw = request.target().get("search_term");
        if (!(raw instanceof String value)) {
            throw invalid("recording target request search_term must be a string");
        }
        return safe(value.trim(), SAFE_ID, "search_term");
    }

    private void validateRequestFeedsDiagnosis(SopEntry candidate, String requestId) {
        Set<String> selectedSignals = new LinkedHashSet<>();
        candidate.anomalyCriteria().stream()
                .filter(criterion -> requestId.equals(criterion.sourceRequestId()))
                .map(AnomalyCriterion::signal)
                .forEach(selectedSignals::add);
        boolean consumed = !selectedSignals.isEmpty()
                && candidate.diagnosisRules().stream()
                .map(DiagnosisRule::requiredSignals)
                .anyMatch(required -> required.stream().anyMatch(selectedSignals::contains));
        if (!consumed) {
            throw invalid(
                    "recording target request must feed a deterministic diagnosis rule");
        }
    }

    private Map<String, String> bindingRefs(JsonNode node) {
        if (!node.isObject()
                || !exactKeys(node, Set.copyOf(CORE_SIGNALS))) {
            throw invalid("recording target must bind the exact three core signals");
        }
        Map<String, String> bindings = new LinkedHashMap<>();
        for (String signal : CORE_SIGNALS) {
            bindings.put(signal, safe(text(node, signal), SAFE_ID, signal));
        }
        return Map.copyOf(bindings);
    }

    private void validateWindow(String value) {
        if (value == null) {
            throw invalid("recording target window must be a bounded relative time");
        }
        Matcher matcher = WINDOW.matcher(value);
        if (!matcher.matches()) {
            throw invalid("recording target window must be a bounded relative time");
        }
        long amount = Long.parseLong(matcher.group(1));
        long seconds = switch (matcher.group(2)) {
            case "s" -> amount;
            case "m" -> Math.multiplyExact(amount, 60L);
            case "h" -> Math.multiplyExact(amount, 3600L);
            case "d" -> Math.multiplyExact(amount, 86400L);
            default -> throw invalid("recording target window unit is invalid");
        };
        if (seconds > 86400L) {
            throw invalid("recording target window exceeds 24 hours");
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalid(field + " must be a non-empty string");
        }
        return value.textValue().trim();
    }

    private String safe(String value, Pattern pattern, String field) {
        if (!pattern.matcher(value).matches()
                || !TroubleshootingSecretRedactor.redact(value).equals(value)) {
            throw invalid(field + " must be a safe identifier");
        }
        return value;
    }

    private boolean exactKeys(JsonNode node, Set<String> expected) {
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private boolean readyOrObserved(GuanceEvidenceReadiness.SignalStatus status) {
        return status == GuanceEvidenceReadiness.SignalStatus.READY_FOR_VALIDATION
                || status
                == GuanceEvidenceReadiness.SignalStatus.CANONICAL_RESULT_OBSERVED;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    public record Target(
            String targetId,
            String system,
            String service,
            String selectorKey,
            String candidateReference,
            String candidateFingerprint,
            String requiredEvidenceRequestId,
            String requestFingerprint,
            String searchTerm,
            String window,
            Map<String, String> bindingRefs) {

        public Target {
            bindingRefs = Map.copyOf(bindingRefs == null ? Map.of() : bindingRefs);
        }
    }

    public record View(
            String contractVersion,
            String system,
            String service,
            String catalogFingerprint,
            int frozenTargetCount,
            int executableTargetCount,
            List<Target> targets,
            long asOfEpochSeconds,
            List<String> blockers) {

        public View {
            targets = List.copyOf(targets == null ? List.of() : targets);
            blockers = List.copyOf(blockers == null ? List.of() : blockers);
        }
    }
}
