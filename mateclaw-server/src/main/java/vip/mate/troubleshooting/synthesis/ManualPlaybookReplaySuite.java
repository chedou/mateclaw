package vip.mate.troubleshooting.synthesis;

import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.SopEntry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server-owned fixed replay suite for one exact manual Playbook selector. */
public record ManualPlaybookReplaySuite(
        String contractVersion,
        String suiteId,
        int suiteVersion,
        String selectorKey,
        RequiredEvidenceRequest requiredEvidenceRequest,
        SopEntry exampleCandidate,
        List<ReplayCase> cases) {

    public static final String CONTRACT_VERSION = "manual-playbook-replay-suite.v1";

    public ManualPlaybookReplaySuite {
        contractVersion = required(contractVersion, "contractVersion");
        if (!CONTRACT_VERSION.equals(contractVersion)) {
            throw new IllegalArgumentException("unsupported manual replay suite contract");
        }
        suiteId = required(suiteId, "suiteId");
        if (suiteVersion < 1) {
            throw new IllegalArgumentException("suiteVersion must be positive");
        }
        selectorKey = required(selectorKey, "selectorKey");
        if (requiredEvidenceRequest == null) {
            throw new IllegalArgumentException("requiredEvidenceRequest is required");
        }
        if (exampleCandidate != null
                && (!selectorKey.equals(exampleCandidate.routingKey())
                || !"candidate".equals(exampleCandidate.status())
                || exampleCandidate.verified())) {
            throw new IllegalArgumentException(
                    "manual replay example must be an unverified candidate for its selector");
        }
        cases = List.copyOf(cases == null ? List.of() : cases);
        if (cases.isEmpty()
                || cases.stream().noneMatch(item -> item.cohort() == Cohort.POSITIVE)
                || cases.stream().noneMatch(
                        item -> item.cohort() == Cohort.NEGATIVE_OR_ABSTAIN)) {
            throw new IllegalArgumentException(
                    "manual replay suite requires positive and negative-or-abstain cases");
        }
        if (cases.stream().map(ReplayCase::caseId).distinct().count() != cases.size()) {
            throw new IllegalArgumentException("manual replay case ids must be unique");
        }
    }

    /** Compatibility constructor for evaluator-focused unit fixtures. */
    public ManualPlaybookReplaySuite(
            String contractVersion,
            String suiteId,
            int suiteVersion,
            String selectorKey,
            RequiredEvidenceRequest requiredEvidenceRequest,
            List<ReplayCase> cases) {
        this(
                contractVersion,
                suiteId,
                suiteVersion,
                selectorKey,
                requiredEvidenceRequest,
                null,
                cases);
    }

    public enum Cohort {
        POSITIVE,
        NEGATIVE_OR_ABSTAIN
    }

    public enum Disposition {
        MATCHED,
        EXCLUDED,
        ABSTAINED
    }

    public record RequiredEvidenceRequest(
            String requestId,
            String signalKind,
            boolean required,
            Map<String, Object> target) {

        public RequiredEvidenceRequest {
            requestId = ManualPlaybookReplaySuite.required(requestId, "requestId");
            signalKind = ManualPlaybookReplaySuite.required(signalKind, "signalKind");
            target = immutableMap(target);
            if (!required || target.isEmpty()) {
                throw new IllegalArgumentException(
                        "server replay request must be required and own a target contract");
            }
        }
    }

    public record ReplayCase(
            String caseId,
            Cohort cohort,
            Disposition expectedDisposition,
            String expectedRuleId,
            List<ReplayEvidence> evidence) {

        public ReplayCase {
            caseId = required(caseId, "caseId");
            if (cohort == null || expectedDisposition == null) {
                throw new IllegalArgumentException(
                        "replay cohort and expected disposition are required");
            }
            expectedRuleId = normalize(expectedRuleId);
            if (expectedDisposition == Disposition.MATCHED && expectedRuleId == null) {
                throw new IllegalArgumentException(
                        "a matched replay expectation requires an exact rule id");
            }
            if (expectedDisposition != Disposition.MATCHED && expectedRuleId != null) {
                throw new IllegalArgumentException(
                        "only a matched replay expectation may name a rule id");
            }
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
            if (evidence.isEmpty()
                    || evidence.stream().map(ReplayEvidence::requestId).distinct().count()
                    != evidence.size()) {
                throw new IllegalArgumentException(
                        "a replay case requires unique evidence request ids");
            }
        }
    }

    public record ReplayEvidence(
            String requestId,
            EvidenceStatus status,
            Map<String, Object> observed) {

        public ReplayEvidence {
            requestId = required(requestId, "requestId");
            if (status == null) {
                throw new IllegalArgumentException("evidence status is required");
            }
            observed = immutableMap(observed);
            if (status == EvidenceStatus.MISSING && !observed.isEmpty()) {
                throw new IllegalArgumentException(
                        "missing replay evidence cannot carry observed facts");
            }
        }
    }

    private static Map<String, Object> immutableMap(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = required(key, "map key");
            if (copy.putIfAbsent(normalizedKey, value) != null) {
                throw new IllegalArgumentException("duplicate replay map key");
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    private static String required(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
