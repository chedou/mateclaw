package vip.mate.troubleshooting.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.troubleshooting.dto.SopRouteCandidate;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.dto.SopRouteResult;
import vip.mate.troubleshooting.model.SopDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SopRouter {

    private static final double LOW_CONFIDENCE_THRESHOLD = 0.35d;

    private final SopRegistryService registryService;

    public SopRouteResult route(long workspaceId, SopRouteRequest request) {
        return route(registryService.listSops(workspaceId), request);
    }

    public SopRouteResult route(List<SopDefinition> sops, SopRouteRequest request) {
        SopRouteRequest req = request == null ? emptyRequest() : request;
        int topK = clampTopK(req.topK());
        String normalizedText = normalizedText(req);
        Map<String, Object> signalMap = signalMap(req);

        List<SopDefinition> fallbackSops = sops.stream()
                .filter(SopDefinition::isFallback)
                .toList();
        SopDefinition fallback = fallbackSops.isEmpty() ? null : fallbackSops.get(0);

        List<ScoredSop> scored = sops.stream()
                .filter(sop -> !sop.isFallback())
                .map(sop -> score(sop, req, normalizedText, signalMap))
                .filter(s -> s.score > 0)
                .sorted(Comparator.comparingDouble((ScoredSop s) -> s.score).reversed()
                        .thenComparing(s -> s.sop.name()))
                .toList();

        List<SopRouteCandidate> candidates = scored.stream()
                .limit(topK)
                .map(s -> SopRouteCandidate.of(s.sop, s.score, s.reason, s.missingSignals, false))
                .toList();

        SopRouteCandidate selected = candidates.isEmpty() ? null : candidates.get(0);
        boolean lowConfidence = selected == null || selected.confidence() < LOW_CONFIDENCE_THRESHOLD;
        boolean usedFallback = false;
        List<String> missingSignals = selected == null ? List.of("no-specialized-sop-match") : selected.missingSignals();

        if (lowConfidence && fallback != null) {
            selected = SopRouteCandidate.of(
                    fallback,
                    Math.max(15.0d, selected == null ? 0.0d : selected.score() * 0.5d),
                    selected == null
                            ? "No specialized SOP matched; routed to systematic debugging fallback."
                            : "Specialized SOP confidence is low; routed to systematic debugging fallback.",
                    missingSignals,
                    true
            );
            usedFallback = true;
            List<SopRouteCandidate> withFallback = new ArrayList<>();
            withFallback.add(selected);
            withFallback.addAll(candidates);
            candidates = withFallback.stream().limit(topK).toList();
        }

        return new SopRouteResult(
                selected,
                candidates,
                lowConfidence,
                usedFallback,
                missingSignals,
                inputSummary(req)
        );
    }

    private ScoredSop score(SopDefinition sop, SopRouteRequest req, String text, Map<String, Object> signals) {
        double score = 0.0d;
        List<String> reasons = new ArrayList<>();
        List<String> missingSignals = new ArrayList<>();
        SkillManifest.TroubleshootingMatch match = sop.match();

        List<String> severities = normalizeList(match == null ? null : match.getSeverities());
        if (!severities.isEmpty()) {
            String severity = normalize(req.severity());
            if (!severity.isBlank() && severities.contains(severity)) {
                score += 30;
                reasons.add("severity matched");
            } else if (severity.isBlank()) {
                missingSignals.add("severity");
            } else {
                score -= 10;
                reasons.add("severity mismatched");
            }
        }

        List<String> labels = normalizeList(match == null ? null : match.getLabels());
        int matchedLabels = 0;
        for (String label : labels) {
            if (hasSignal(signals, label)) {
                score += 5;
                matchedLabels++;
            } else {
                missingSignals.add(label);
            }
        }
        if (!labels.isEmpty()) {
            reasons.add("labels " + matchedLabels + "/" + labels.size());
        }

        int keywordHits = 0;
        for (String keyword : normalizeList(match == null ? null : match.getKeywords())) {
            if (!keyword.isBlank() && text.contains(keyword)) {
                keywordHits++;
            }
        }
        if (keywordHits > 0) {
            score += Math.min(48, keywordHits * 12);
            reasons.add(keywordHits + " keyword hits");
        }

        if (text.contains(normalize(sop.domain())) || text.contains(normalize(sop.scenario()).replace('_', ' '))) {
            score += 10;
            reasons.add("domain/scenario signal");
        }

        if (score < 0) score = 0;
        String reason = reasons.isEmpty() ? "No strong deterministic signal." : String.join("; ", reasons);
        return new ScoredSop(sop, score, reason, missingSignals);
    }

    private static boolean hasSignal(Map<String, Object> signals, String label) {
        String key = normalize(label);
        if (signals.containsKey(key)) return true;
        String snake = key.replace("-", "_").replace(" ", "_");
        return signals.containsKey(snake);
    }

    private static Map<String, Object> signalMap(SopRouteRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "eventId", req.eventId());
        put(out, "source", req.source());
        put(out, "severity", req.severity());
        put(out, "alertName", req.alertName());
        put(out, "status", req.status());
        put(out, "serviceName", req.serviceName());
        put(out, "env", req.env());
        put(out, "cluster", req.cluster());
        put(out, "namespace", req.namespace());
        put(out, "pod", req.pod());
        put(out, "instance", req.instance());
        put(out, "endpoint", req.endpoint());
        put(out, "metricName", req.metricName());
        req.safeLabels().forEach((k, v) -> {
            if (k != null && v != null && !v.toString().isBlank()) {
                out.put(normalize(k), v);
            }
        });
        return out;
    }

    private static void put(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isBlank()) {
            out.put(normalize(key), value);
        }
    }

    private static String normalizedText(SopRouteRequest req) {
        StringBuilder sb = new StringBuilder();
        append(sb, req.eventId());
        append(sb, req.source());
        append(sb, req.severity());
        append(sb, req.alertName());
        append(sb, req.status());
        append(sb, req.serviceName());
        append(sb, req.env());
        append(sb, req.cluster());
        append(sb, req.namespace());
        append(sb, req.pod());
        append(sb, req.instance());
        append(sb, req.endpoint());
        append(sb, req.metricName());
        append(sb, req.message());
        append(sb, req.rawText());
        req.safeLabels().forEach((k, v) -> {
            append(sb, k);
            append(sb, v == null ? null : v.toString());
        });
        return normalize(sb.toString());
    }

    private static void append(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(' ').append(value);
        }
    }

    private static List<String> normalizeList(List<String> raw) {
        if (raw == null) return List.of();
        return raw.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(SopRouter::normalize)
                .toList();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static int clampTopK(Integer raw) {
        if (raw == null) return 5;
        return Math.min(10, Math.max(1, raw));
    }

    private static String inputSummary(SopRouteRequest req) {
        List<String> parts = new ArrayList<>();
        if (req.severity() != null) parts.add(req.severity());
        if (req.serviceName() != null) parts.add(req.serviceName());
        if (req.alertName() != null) parts.add(req.alertName());
        if (req.endpoint() != null) parts.add(req.endpoint());
        return parts.isEmpty() ? "unstructured alert" : String.join(" / ", parts);
    }

    private static SopRouteRequest emptyRequest() {
        return new SopRouteRequest(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, Map.of(), 5);
    }

    private record ScoredSop(SopDefinition sop, double score, String reason, List<String> missingSignals) {}
}
