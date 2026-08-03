package vip.mate.troubleshooting.synthesis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;
import vip.mate.troubleshooting.model.SopEntry;

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
    private static final int MAX_REJECTION_REFERENCE_LENGTH = 128;

    private final Map<String, ResolvedSuite> suites;
    private final List<RejectedSeed> rejectedSeeds;
    private final ManualPlaybookReplayFingerprint fingerprints;

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
        this.fingerprints = fingerprints;
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
                addFixed(loaded, resolve(
                        suite,
                        KnowledgeEvidenceGrade.AUTHORED_FIXTURE,
                        fingerprints,
                        evaluator));
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
                                generated,
                                KnowledgeEvidenceGrade.RECORDED_AGGREGATE,
                                fingerprints,
                                evaluator);
                        if (loaded.putIfAbsent(generated.selectorKey(), resolved) != null) {
                            throw new IllegalArgumentException(
                                    "manual replay selectors must be unique");
                        }
                    } catch (Exception failure) {
                        RejectedSeed item = new RejectedSeed(
                                reference, "INVALID_RECORDED_EVIDENCE_SEED");
                        rejected.add(item);
                        // 原因必须说出来。隔离本身是对的（fail-closed，种子不加载），
                        // 但只报一个代码就等于逼作者去猜——猜的过程里最省事的做法
                        // 是把校验放宽，而那正是这道闸门要挡住的事。
                        // 消息脱敏并截断：种子里可能带业务串，日志不是它该去的地方。
                        log.warn("[manual-replay] quarantined recorded seed {} ({}): {}",
                                item.reference(), item.code(), safeReason(failure));
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

    /**
     * 这条候选被批准后应当记什么成色——**它不回答「能不能被批准」**。
     *
     * <p><b>这两件事此前是同一个方法。</b> {@link #evidenceGrade} 只在候选与随包
     * 示例逐字节相同时才返回值，而促成物读取器把「没有成色」直接当成「没有可路由
     * 的 promotion artifact」而拒绝。合起来的效果是：把示例改一个字，评审面板显示
     * ELIGIBLE_FOR_APPROVAL，点批准得到 409——**产品里没有「写一条自己的知识」这
     * 条路**。指纹比对是「什么成色」的正确答案，是「能不能批准」的错误答案；一道
     * 指着错误对象的闸门比没有闸门更糟。</p>
     *
     * <p>能不能批准，交回给评审资格那道闸门去判——它把原因写在评审面板上，作者
     * 看得见，也就不必靠猜；而猜的时候最省事的做法，正是把校验放宽。</p>
     */
    public KnowledgeEvidenceGrade promotionGrade(
            String selectorKey,
            SopEntry candidate) {
        return evidenceGrade(selectorKey, candidate)
                // 逐字节等于随包示例：继承套件自己的成色（阈值来自录制聚合）。
                .orElseGet(() -> find(selectorKey).isPresent()
                        // 有套件、但阈值是作者自己写的：能走到批准就说明它过了回放，
                        // 而回放比对的期望值并非来自真实聚合。
                        ? KnowledgeEvidenceGrade.AUTHORED_FIXTURE
                        // 连套件都没有：没有任何东西证明过它（A13）。
                        : KnowledgeEvidenceGrade.UNVERIFIED);
    }

    /** Returns the suite's own recorded grade only for the exact server-owned example. */
    public Optional<KnowledgeEvidenceGrade> evidenceGrade(
            String selectorKey,
            SopEntry candidate) {
        if (candidate == null) {
            return Optional.empty();
        }
        String candidateFingerprint = fingerprints.candidate(candidate);
        return find(selectorKey)
                .filter(resolved -> candidateFingerprint.equals(
                        fingerprints.candidate(resolved.suite().exampleCandidate())))
                .map(ResolvedSuite::evidenceGrade);
    }

    private ResolvedSuite resolve(
            ManualPlaybookReplaySuite suite,
            KnowledgeEvidenceGrade evidenceGrade,
            ManualPlaybookReplayFingerprint fingerprints,
            ManualPlaybookReplayEvaluator evaluator) {
        if (suite.exampleCandidate() == null
                || !evaluator.evaluate(suite.exampleCandidate(), suite).passed()) {
            throw new IllegalArgumentException(
                    "manual replay suite example must pass its own cases");
        }
        return new ResolvedSuite(suite, fingerprints.suite(suite), evidenceGrade);
    }

    private void addFixed(
            Map<String, ResolvedSuite> loaded,
            ResolvedSuite resolved) {
        if (loaded.putIfAbsent(resolved.suite().selectorKey(), resolved) != null) {
            throw new IllegalArgumentException("manual replay selectors must be unique");
        }
    }

    /** Bounded, redacted reason so an author can act without reading this class. */
    private static String safeReason(Exception failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getSimpleName();
        }
        String redacted = TroubleshootingSecretRedactor.redact(message);
        return redacted.length() > 300 ? redacted.substring(0, 300) + "…" : redacted;
    }

    private String seedReference(JsonNode node, int index) {
        String fallback = "recordedEvidenceSeeds[" + index + "]";
        String selector = node.path("selectorKey").asText("").trim();
        if (selector.isEmpty()
                || selector.length() > MAX_REJECTION_REFERENCE_LENGTH
                || !TroubleshootingSecretRedactor.redact(selector).equals(selector)) {
            return fallback;
        }
        return selector;
    }

    public record ResolvedSuite(
            ManualPlaybookReplaySuite suite,
            String fingerprint,
            KnowledgeEvidenceGrade evidenceGrade) {

        /** Compatibility shape for test doubles; never grants recorded authority. */
        public ResolvedSuite(
                ManualPlaybookReplaySuite suite,
                String fingerprint) {
            this(suite, fingerprint, KnowledgeEvidenceGrade.UNVERIFIED);
        }

        public ResolvedSuite {
            if (suite == null
                    || fingerprint == null
                    || !fingerprint.matches("[a-f0-9]{64}")
                    || evidenceGrade == null) {
                throw new IllegalArgumentException(
                        "resolved replay suite and SHA-256 fingerprint are required");
            }
        }
    }

    public record RejectedSeed(String reference, String code) {
        public RejectedSeed {
            reference = reference == null ? null : reference.trim();
            if (reference == null || reference.isEmpty()
                    || reference.length() > MAX_REJECTION_REFERENCE_LENGTH
                    || !TroubleshootingSecretRedactor.redact(reference).equals(reference)
                    || code == null || !code.matches("[A-Z0-9_]+")) {
                throw new IllegalArgumentException(
                        "recorded seed rejection requires a bounded reference and code");
            }
        }
    }
}
