package vip.mate.troubleshooting.synthesis;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.TroubleshootingSafetyPolicy;
import vip.mate.troubleshooting.evidence.EvidenceProperties;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fixture-confined P1 SOP learning lane.
 *
 * <p>{@link #preview(long, SopSynthesisRequest)} stops after bounded evidence
 * compression. {@link #generate(long, PlaybookSynthesisRequest)} adds one
 * structured induction, deterministic validation/reference comparison, and a
 * review-only candidate. Neither path can promote an active playbook. Until a
 * workspace-to-observability asset mapping exists, both remain confined to
 * explicitly registered fixture scopes and the recorded replay adapter.</p>
 */
@Service
public final class SopSynthesisService {

    private static final String SEARCH_REQUEST_ID = "SYNTH-LOG-SEARCH";
    private static final String TRACE_REQUEST_ID = "SYNTH-TRACE-BUNDLE";
    private static final String CONTRAST_REQUEST_ID = "SYNTH-CONTRAST-SAMPLE";
    private static final Pattern SAFE_TARGET =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");
    private static final Pattern SAFE_WINDOW = Pattern.compile("-?([1-9][0-9]*)([smhd])");
    private static final long MAX_LOOKBACK_SECONDS = Duration.ofHours(24).toSeconds();
    private static final int MAX_WINDOW_CHARS = 12;
    private static final int MAX_REFERENCE_SOURCE_CHARS = 256;
    private static final Set<String> FIXTURE_ONLY_SOURCES = Set.of("recorded-replay");

    private final EvidenceSourceRouter evidenceRouter;
    private final DeterministicLogTraceCompressor compressor;
    private final EvidenceProperties.SynthesisPreview previewPolicy;
    private final Clock clock;
    private final PlaybookDraftInducer inducer;
    private final PlaybookDraftValidator validator;
    private final PlaybookCandidateStore candidateStore;
    private final ReferenceSolutionComparator referenceComparator =
            new ReferenceSolutionComparator();

    @Autowired
    public SopSynthesisService(
            EvidenceSourceRouter evidenceRouter,
            DeterministicLogTraceCompressor compressor,
            EvidenceProperties properties,
            PlaybookDraftInducer inducer,
            PlaybookDraftValidator validator,
            PlaybookCandidateStore candidateStore) {
        this(evidenceRouter, compressor, previewPolicy(properties), Clock.systemUTC(),
                inducer, validator, candidateStore);
    }

    public SopSynthesisService(
            EvidenceSourceRouter evidenceRouter,
            DeterministicLogTraceCompressor compressor) {
        this(evidenceRouter, compressor, previewPolicy(null), Clock.systemUTC(),
                null, null, null);
    }

    SopSynthesisService(
            EvidenceSourceRouter evidenceRouter,
            DeterministicLogTraceCompressor compressor,
            Clock clock) {
        this(evidenceRouter, compressor, previewPolicy(null), clock, null, null, null);
    }

    SopSynthesisService(
            EvidenceSourceRouter evidenceRouter,
            DeterministicLogTraceCompressor compressor,
            Clock clock,
            PlaybookDraftInducer inducer,
            PlaybookDraftValidator validator,
            PlaybookCandidateStore candidateStore) {
        this(evidenceRouter, compressor, previewPolicy(null), clock,
                inducer, validator, candidateStore);
    }

    private SopSynthesisService(
            EvidenceSourceRouter evidenceRouter,
            DeterministicLogTraceCompressor compressor,
            EvidenceProperties.SynthesisPreview previewPolicy,
            Clock clock,
            PlaybookDraftInducer inducer,
            PlaybookDraftValidator validator,
            PlaybookCandidateStore candidateStore) {
        this.evidenceRouter = evidenceRouter;
        this.compressor = compressor;
        this.previewPolicy = previewPolicy;
        this.clock = clock;
        this.inducer = inducer;
        this.validator = validator;
        this.candidateStore = candidateStore;
    }

    public SopSynthesisPreview preview(long workspaceId, SopSynthesisRequest request) {
        if (workspaceId <= 0 || request == null) {
            throw invalid("workspaceId and synthesis request are required");
        }
        if (!safeIdentifier(request.system()) || !safeIdentifier(request.service())) {
            throw invalid("system and service must be mapped safe identifiers");
        }
        if (!safeIdentifier(request.searchTerm())) {
            throw invalid("searchTerm must be a mapped safe error code or scenario keyword");
        }
        ValidatedWindow window = validatedWindow(request.window());
        Instant occurredAt = validatedOccurredAt(request.occurredAt(), window.duration());
        requireFixtureScope(workspaceId, request);

        IncidentContext incident = incident(request, occurredAt);
        EvidenceResult search = collect(
                new EvidenceRequest(
                        SEARCH_REQUEST_ID,
                        "log_search",
                        "sample logs and extract PS ID for SOP synthesis",
                        Map.of("search_term", request.searchTerm()),
                        window.expression(),
                        true),
                incident,
                "log_search");
        long matchCount = positiveLong(search.observed().get("match_count"), "match_count");
        String psId = safePsId(search.observed().get("ps_id"));

        EvidenceResult trace = collect(
                new EvidenceRequest(
                        TRACE_REQUEST_ID,
                        "log_trace_bundle",
                        "collect the bounded cross-service trace before deterministic compression",
                        Map.of("ps_id", psId),
                        window.expression(),
                        true),
                incident,
                "log_trace_bundle");

        EvidenceResult contrast = collectOptional(
                new EvidenceRequest(
                        CONTRAST_REQUEST_ID,
                        "contrast_sample",
                        "compare same-window successful requests with the failed scenario",
                        Map.of(
                                "scenario_key", request.searchTerm(),
                                "exclude_ps_id", psId),
                        window.expression(),
                        false),
                incident);

        LogTraceSkeleton skeleton;
        try {
            skeleton = compressor.compress(trace);
        } catch (IllegalArgumentException malformed) {
            throw unavailable("log_trace_bundle cannot be compressed safely");
        }
        boolean contrastMalformed = false;
        if (contrast != null) {
            try {
                skeleton = compressor.compress(trace, contrast);
            } catch (IllegalArgumentException malformedContrast) {
                contrast = null;
                contrastMalformed = true;
            }
        }
        if (!psId.equals(skeleton.psId())) {
            throw unavailable("log_search and log_trace_bundle returned different PS IDs");
        }

        List<String> warnings = new ArrayList<>();
        warnings.add("该 evidencePreview 仅表示取证与确定性压缩完成；本对象自身尚未调用模型或创建 candidate。");
        warnings.add("当前证据源为 fixture recorded-replay；真实 PS ID 贯通与 DQL 字段待 P2 内网验证。");
        if (contrast == null) {
            warnings.add(contrastMalformed
                    ? "成功样本对照格式无效，已确定性降级为 contrastAvailable=false。"
                    : "成功样本对照不可用，草案可继续生成但锁定校准期资格。" );
        }

        return new SopSynthesisPreview(
                SopSynthesisPreview.Stage.READY_FOR_MODEL,
                request.system(),
                request.service(),
                request.searchTerm(),
                matchCount,
                psId,
                evidenceReference(search),
                evidenceReference(trace),
                contrast == null ? null : evidenceReference(contrast),
                skeleton,
                TroubleshootingSafetyPolicy.EVIDENCE_IS_FIXTURE,
                warnings);
    }

    /** Runs the complete P1 fixture lane and persists only a review-only candidate. */
    public PlaybookSynthesisResult generate(
            long workspaceId,
            PlaybookSynthesisRequest request) {
        if (inducer == null || validator == null || candidateStore == null) {
            throw new MateClawException(
                    "err.troubleshooting.synthesis_not_configured", 503,
                    "Playbook synthesis generation dependencies are not configured");
        }
        if (request == null || !safeIdentifier(request.sourceIncidentId())) {
            throw invalid("a mapped safe sourceIncidentId is required");
        }
        Instant started = Instant.now(clock);
        if (request.readyAt().isAfter(started)) {
            throw invalid("readyAt cannot be in the future");
        }

        SopSynthesisPreview preview = preview(workspaceId, request.evidenceRequest());
        PlaybookDraftInducer.InductionResult induction = inducer.induce(modelInput(preview));

        if (induction.status() == PlaybookDraftInducer.Status.REJECTED) {
            NorthStarTimings timings = concludedTimings(request);
            return new PlaybookSynthesisResult(
                    PlaybookSynthesisResult.Stage.MODEL_REJECTED,
                    preview, null, null, timings, induction.errors(), preview.warnings());
        }
        if (induction.status() == PlaybookDraftInducer.Status.ABSTAINED) {
            NorthStarTimings timings = concludedTimings(request);
            return new PlaybookSynthesisResult(
                    PlaybookSynthesisResult.Stage.ABSTAINED,
                    preview, null, null, timings,
                    List.of(induction.abstainReason()), preview.warnings());
        }
        if (induction.invocation() == null || induction.proposal() == null) {
            NorthStarTimings timings = concludedTimings(request);
            return new PlaybookSynthesisResult(
                    PlaybookSynthesisResult.Stage.MODEL_REJECTED,
                    preview, null, null, timings,
                    List.of("MODEL_RESULT_INCOMPLETE"), preview.warnings());
        }

        String evidenceBundleId = evidenceBundleId(preview);
        String generationKey = hash(
                workspaceId + "|" + request.sourceIncidentId() + "|" + evidenceBundleId
                        + "|" + induction.invocation().modelConfigVersion()
                        + "|" + PlaybookDraft.CONTRACT_VERSION);
        PlaybookDraft draft = draft(
                request, preview, induction.proposal(), induction.invocation(), generationKey);
        PlaybookDraftValidator.ValidationResult validation = validator.validate(
                draft,
                new PlaybookDraftValidator.ValidationContext(
                        preview.system(), preview.searchTerm(), evidenceKinds(preview),
                        preview.contrastAvailable()));
        if (!validation.valid()) {
            PlaybookDraft rejected = draft.withValidationErrors(validation.errors());
            NorthStarTimings timings = concludedTimings(request);
            return new PlaybookSynthesisResult(
                    PlaybookSynthesisResult.Stage.VALIDATION_REJECTED,
                    preview, null, rejected, timings,
                    validation.errors().stream()
                            .map(error -> error.code() + ":" + error.fieldPath())
                            .toList(),
                    preview.warnings());
        }

        ReferenceSolutionComparator.Comparison comparison = referenceComparator.compare(
                draft, ReferenceSolution.messageSendFailure());
        List<String> eligibilityReasons = new ArrayList<>();
        eligibilityReasons.add("P1_CALIBRATION_PERIOD");
        if (!preview.contrastAvailable()) {
            eligibilityReasons.add("CONTRAST_UNAVAILABLE");
        }
        if (!comparison.passed()) {
            eligibilityReasons.add("REFERENCE_SOLUTION_DELTA");
        }
        Instant conclusionAt = Instant.now(clock);
        NorthStarTimings timings = timings(request, conclusionAt);
        PlaybookKnowledgeRecord candidate = new PlaybookKnowledgeRecord(
                "candidate-" + generationKey.substring(0, 24),
                draft,
                "EVIDENCE_DERIVED",
                "CANDIDATE",
                "VALID",
                "",
                "",
                evidenceBundleId,
                preview.service(),
                comparison,
                "NOT_ELIGIBLE",
                eligibilityReasons,
                preview.fixtureMode(),
                timings,
                conclusionAt);
        PlaybookCandidateStore.StoredCandidate stored = candidateStore.saveOrGet(
                workspaceId, candidate);
        return new PlaybookSynthesisResult(
                stored.created()
                        ? PlaybookSynthesisResult.Stage.CANDIDATE_CREATED
                        : PlaybookSynthesisResult.Stage.CANDIDATE_REUSED,
                preview, stored.candidate(), null, timings, List.of(), preview.warnings());
    }

    private IncidentContext incident(SopSynthesisRequest request, Instant occurredAt) {
        return new IncidentContext(
                "synthesis-preview-" + occurredAt.toEpochMilli(),
                request.system(),
                request.service(),
                null,
                "SOP synthesis preview",
                "P2",
                "preview only",
                null,
                occurredAt,
                null,
                "synthesis_preview",
                IncidentCompleteness.LOG,
                null);
    }

    private EvidenceResult collect(
            EvidenceRequest request,
            IncidentContext incident,
            String stage) {
        EvidenceResult raw = evidenceRouter.collect(
                request, incident, FIXTURE_ONLY_SOURCES);
        if (raw == null) {
            throw unavailable(stage + " returned no evidence");
        }
        if (!request.requestId().equals(raw.queryId())) {
            throw unavailable(stage + " returned an unexpected evidence id");
        }
        if (raw.status() == EvidenceStatus.MISSING) {
            throw unavailable(stage + " evidence is missing");
        }
        return raw;
    }

    private EvidenceResult collectOptional(
            EvidenceRequest request,
            IncidentContext incident) {
        try {
            EvidenceResult raw = evidenceRouter.collect(
                    request, incident, FIXTURE_ONLY_SOURCES);
            if (raw == null
                    || !request.requestId().equals(raw.queryId())
                    || raw.status() == EvidenceStatus.MISSING) {
                return null;
            }
            return raw;
        } catch (RuntimeException sourceFailure) {
            return null;
        }
    }

    private SynthesisModelInput modelInput(SopSynthesisPreview preview) {
        List<SynthesisModelInput.EvidenceDescriptor> evidence = new ArrayList<>();
        evidence.add(new SynthesisModelInput.EvidenceDescriptor(
                preview.searchEvidence().queryId(), "log_search"));
        evidence.add(new SynthesisModelInput.EvidenceDescriptor(
                preview.traceEvidence().queryId(), "log_trace_bundle"));
        if (preview.contrastEvidence() != null) {
            evidence.add(new SynthesisModelInput.EvidenceDescriptor(
                    preview.contrastEvidence().queryId(), "contrast_sample"));
        }
        return new SynthesisModelInput(
                preview.system(), preview.service(), preview.searchTerm(),
                evidence, preview.skeleton());
    }

    private Map<String, String> evidenceKinds(SopSynthesisPreview preview) {
        Map<String, String> kinds = new LinkedHashMap<>();
        kinds.put(preview.searchEvidence().queryId(), "log_search");
        kinds.put(preview.traceEvidence().queryId(), "log_trace_bundle");
        if (preview.contrastEvidence() != null) {
            kinds.put(preview.contrastEvidence().queryId(), "contrast_sample");
        }
        return Map.copyOf(kinds);
    }

    private NorthStarTimings timings(
            PlaybookSynthesisRequest request,
            Instant conclusionAt) {
        try {
            request.reportedAt().toEpochMilli();
            request.readyAt().toEpochMilli();
            conclusionAt.toEpochMilli();
            return NorthStarTimings.concluded(
                    request.reportedAt(), request.readyAt(), conclusionAt);
        } catch (ArithmeticException | DateTimeException | IllegalArgumentException invalidTime) {
            throw invalid("north-star timestamps are invalid or non-chronological");
        }
    }

    private NorthStarTimings concludedTimings(PlaybookSynthesisRequest request) {
        return timings(request, Instant.now(clock));
    }

    private String evidenceBundleId(SopSynthesisPreview preview) {
        StringBuilder canonical = new StringBuilder(2048);
        canonical.append(preview.system()).append('|')
                .append(preview.service()).append('|')
                .append(preview.searchTerm()).append('|')
                .append(preview.matchCount()).append('|')
                .append(preview.psId()).append('|');
        appendEvidence(canonical, preview.searchEvidence());
        appendEvidence(canonical, preview.traceEvidence());
        if (preview.contrastEvidence() != null) {
            appendEvidence(canonical, preview.contrastEvidence());
        }
        canonical.append(preview.skeleton());
        return "evidence-bundle-" + hash(canonical.toString()).substring(0, 32);
    }

    private void appendEvidence(
            StringBuilder canonical,
            SopSynthesisPreview.EvidenceReference evidence) {
        canonical.append(evidence.queryId()).append('|')
                .append(evidence.status()).append('|')
                .append(evidence.source()).append('|')
                .append(evidence.collectedAt()).append('|');
    }

    private PlaybookDraft draft(
            PlaybookSynthesisRequest request,
            SopSynthesisPreview preview,
            PlaybookDraftProposal proposal,
            PlaybookDraftInducer.ModelInvocation invocation,
            String generationKey) {
        return new PlaybookDraft(
                "draft-" + generationKey.substring(0, 24),
                generationKey,
                request.sourceIncidentId(),
                proposal.proposedType(),
                proposal.proposedSelector(),
                proposal.title(),
                proposal.evidencePlan(),
                proposal.criteria(),
                proposal.diagnosisHypotheses(),
                proposal.humanActions(),
                proposal.evidenceCitations(),
                new PlaybookDraft.ModelProvenance(
                        invocation.provider(),
                        invocation.modelName(),
                        invocation.modelConfigVersion(),
                        PlaybookDraft.CONTRACT_VERSION,
                        invocation.calledAt(),
                        invocation.invocationCount()),
                preview.contrastAvailable(),
                List.of());
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private void requireFixtureScope(long workspaceId, SopSynthesisRequest request) {
        if (previewPolicy == null || workspaceId != previewPolicy.getFixtureWorkspaceId()) {
            throw forbidden("synthesis preview is not registered for this workspace");
        }
        Map<String, List<String>> scopes = previewPolicy.getFixtureServices();
        boolean permitted = scopes != null && scopes.entrySet().stream()
                .filter(entry -> entry.getKey() != null
                        && entry.getKey().equalsIgnoreCase(request.system()))
                .map(Map.Entry::getValue)
                .filter(services -> services != null)
                .flatMap(List::stream)
                .anyMatch(service -> service != null
                        && service.equalsIgnoreCase(request.service()));
        if (!permitted) {
            throw forbidden("synthesis preview is not registered for this system/service");
        }
    }

    private boolean safeIdentifier(String value) {
        return SAFE_TARGET.matcher(value).matches()
                && value.equals(TroubleshootingSecretRedactor.redact(value));
    }

    private ValidatedWindow validatedWindow(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > MAX_WINDOW_CHARS) {
            throw invalid("synthesis window must be between 1 second and 24 hours");
        }
        Matcher matcher = SAFE_WINDOW.matcher(value);
        if (!matcher.matches()) {
            throw invalid("unsupported synthesis window");
        }
        try {
            long amount = Long.parseLong(matcher.group(1));
            long multiplier = switch (matcher.group(2)) {
                case "s" -> 1L;
                case "m" -> 60L;
                case "h" -> 3_600L;
                case "d" -> 86_400L;
                default -> throw invalid("unsupported synthesis window unit");
            };
            if (amount > MAX_LOOKBACK_SECONDS / multiplier) {
                throw invalid("synthesis window must be between 1 second and 24 hours");
            }
            Duration duration = Duration.ofSeconds(amount * multiplier);
            return new ValidatedWindow(
                    "-" + amount + matcher.group(2), duration);
        } catch (NumberFormatException overflow) {
            throw invalid("unsupported synthesis window");
        }
    }

    private Instant validatedOccurredAt(Instant requested, Duration lookback) {
        Instant occurredAt = requested == null ? Instant.now(clock) : requested;
        try {
            occurredAt.toEpochMilli();
            occurredAt.minus(lookback).toEpochMilli();
            return occurredAt;
        } catch (ArithmeticException | DateTimeException invalidTime) {
            throw invalid("occurredAt is outside the supported epoch range");
        }
    }

    private SopSynthesisPreview.EvidenceReference evidenceReference(
            EvidenceResult evidence) {
        String source = evidence.source();
        if (source.length() > MAX_REFERENCE_SOURCE_CHARS) {
            throw unavailable("evidence source exceeds the safe character bound");
        }
        try {
            evidence.collectedAt().toEpochMilli();
        } catch (ArithmeticException invalidTime) {
            throw unavailable("evidence collection time is outside the supported epoch range");
        }
        return new SopSynthesisPreview.EvidenceReference(
                evidence.queryId(),
                evidence.status(),
                TroubleshootingSecretRedactor.redact(source),
                evidence.collectedAt());
    }

    private long positiveLong(Object raw, String field) {
        if (!(raw instanceof Number number)) {
            throw unavailable(field + " is missing or malformed");
        }
        try {
            long value = new BigDecimal(String.valueOf(number)).longValueExact();
            if (value <= 0) {
                throw unavailable(field + " must be positive");
            }
            return value;
        } catch (ArithmeticException invalid) {
            throw unavailable(field + " must be an integer");
        }
    }

    private String safePsId(Object raw) {
        if (!(raw instanceof String value)
                || !SAFE_TARGET.matcher(value.trim()).matches()) {
            throw unavailable("log_search returned no safe PS ID");
        }
        return value.trim();
    }

    private MateClawException invalid(String message) {
        return new MateClawException(
                "err.troubleshooting.invalid_synthesis_request", 400, message);
    }

    private MateClawException unavailable(String message) {
        return new MateClawException(
                "err.troubleshooting.synthesis_evidence_missing", 409, message);
    }

    private MateClawException forbidden(String message) {
        return new MateClawException(
                "err.troubleshooting.synthesis_scope_forbidden", 403, message);
    }

    private static EvidenceProperties.SynthesisPreview previewPolicy(
            EvidenceProperties properties) {
        EvidenceProperties source = properties == null ? new EvidenceProperties() : properties;
        return source.getSynthesisPreview();
    }

    private record ValidatedWindow(String expression, Duration duration) {
    }
}
