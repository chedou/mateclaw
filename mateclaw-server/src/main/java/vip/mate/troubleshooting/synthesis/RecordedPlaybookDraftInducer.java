package vip.mate.troubleshooting.synthesis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import vip.mate.llm.chatmodel.ProviderChatModelFactory;
import vip.mate.llm.service.ModelConfigService;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Replaces the single model call with a server-owned recorded response so the
 * learning loop is walkable without a configured provider.
 *
 * <p><b>What this is not.</b> It does not skip induction and it does not pretend
 * a model is online. Exactly one step changes — <em>what the model said</em> —
 * and every downstream step runs unmodified: deterministic validation, the
 * reference-solution comparison, candidate persistence, and generation-key
 * idempotence. A recorded response that would be rejected still gets rejected.</p>
 *
 * <p><b>Why it has to exist.</b> The blueprint's only named "must pass first"
 * acceptance case is the no-error-code one, and it necessarily costs one model
 * call. Without this, that case needs a live provider plus credentials, so it
 * was the one path nobody could walk by default — the same conjunction-of-gates
 * problem the hit path had before the demo seeder, one layer up.</p>
 *
 * <p><b>Provenance self-declares.</b> The invocation reports
 * {@code provider=recorded}; it never borrows a real provider name. Anyone
 * reading the stored candidate can see at a glance that no model was actually
 * called, in the same way {@code approvedBy=ts-demo-seeder} shows no human
 * reviewed the demo Playbook.</p>
 *
 * <p>Off unless {@code mateclaw.troubleshooting.demo.enabled=true}. A request
 * with no recorded response falls back to the real inducer, so enabling the demo
 * never silently answers for a case it has not recorded.</p>
 */
@Service
@Primary
@ConditionalOnProperty(prefix = "mateclaw.troubleshooting.demo", name = "enabled",
        havingValue = "true")
public class RecordedPlaybookDraftInducer extends PlaybookDraftInducer {

    /** Deliberately not a real provider id. It must be obvious in the ledger. */
    static final String RECORDED_PROVIDER = "recorded";
    static final String RECORDED_MODEL_NAME = "recorded-demo-draft";

    private static final Logger log =
            LoggerFactory.getLogger(RecordedPlaybookDraftInducer.class);
    private static final String RESOURCE =
            "troubleshooting/synthesis/recorded-draft-proposals.json";

    private final Map<String, Recorded> byKey;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // Two constructors, so the injectable one must be named explicitly.
    @Autowired
    public RecordedPlaybookDraftInducer(
            ModelConfigService modelConfigService,
            ProviderChatModelFactory chatModelFactory,
            ObjectMapper objectMapper) {
        this(modelConfigService, chatModelFactory, objectMapper,
                new ClassPathResource(RESOURCE), Clock.systemUTC());
    }

    RecordedPlaybookDraftInducer(
            ModelConfigService modelConfigService,
            ProviderChatModelFactory chatModelFactory,
            ObjectMapper objectMapper,
            Resource resource,
            Clock clock) {
        super(modelConfigService, chatModelFactory, objectMapper, clock);
        this.objectMapper = objectMapper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.byKey = load(objectMapper, resource);
    }

    @Override
    public InductionResult induce(SynthesisModelInput input) {
        if (input == null) {
            throw new IllegalArgumentException("synthesis model input is required");
        }
        Recorded recorded = byKey.get(key(
                input.system(), input.service(), input.scenarioKey()));
        if (recorded == null) {
            // No recording for this case: fall through to the real model rather
            // than answer for a case nobody recorded.
            log.debug("[ts-demo] no recorded draft proposal for {}/{}/{}",
                    input.system(), input.service(), input.scenarioKey());
            return super.induce(input);
        }
        log.info("[ts-demo] using recorded draft proposal {} — no model was called;"
                        + " downstream validation, reference comparison and candidate"
                        + " persistence are unchanged",
                recorded.sourceReference());
        PlaybookDraftProposal proposal = recorded.proposal();
        return new InductionResult(
                proposal.abstain() ? Status.ABSTAINED : Status.ACCEPTED,
                proposal.abstain() ? null : proposal,
                proposal.abstainReason(),
                new ModelInvocation(
                        RECORDED_PROVIDER,
                        RECORDED_MODEL_NAME,
                        recorded.sourceReference(),
                        Instant.now(clock),
                        1),
                List.of());
    }

    /** Exposed for the test that locks the recording against the validator. */
    static Map<String, Recorded> load(ObjectMapper objectMapper, Resource resource) {
        try (InputStream stream = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(stream);
            if (root.path("version").asInt() != 1) {
                throw new IllegalArgumentException("unsupported recorded proposal catalog");
            }
            Map<String, Recorded> loaded = new LinkedHashMap<>();
            for (JsonNode node : root.path("proposals")) {
                String system = required(node, "system");
                String service = required(node, "service");
                String scenarioKey = required(node, "scenarioKey");
                Recorded recorded = new Recorded(
                        system, service, scenarioKey,
                        required(node, "sourceReference"),
                        objectMapper.treeToValue(
                                node.get("proposal"), PlaybookDraftProposal.class));
                if (loaded.putIfAbsent(key(system, service, scenarioKey), recorded) != null) {
                    throw new IllegalArgumentException(
                            "duplicate recorded proposal for " + scenarioKey);
                }
            }
            return Map.copyOf(loaded);
        } catch (Exception failure) {
            throw new IllegalStateException(
                    "bundled recorded draft proposal catalog is invalid", failure);
        }
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String key(String system, String service, String scenarioKey) {
        return norm(system) + "|" + norm(service) + "|" + norm(scenarioKey);
    }

    private static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    record Recorded(
            String system,
            String service,
            String scenarioKey,
            String sourceReference,
            PlaybookDraftProposal proposal) {
    }
}
