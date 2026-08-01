package vip.mate.troubleshooting.synthesis;

import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.SopEntry;

import java.util.List;
import java.util.Map;

/** Server-owned positive replay evidence from which deterministic cases are generated. */
public record ManualPlaybookRecordedEvidenceSeed(
        String contractVersion,
        String suiteId,
        int suiteVersion,
        String selectorKey,
        String requiredEvidenceRequestId,
        String sourceReference,
        SopEntry exampleCandidate,
        ManualPlaybookReplaySuite.ReplayCase positiveCase) {

    public static final String CONTRACT_VERSION =
            "manual-playbook-recorded-evidence-seed.v1";
    private static final int MAX_AGGREGATE_DEPTH = 4;
    private static final int MAX_AGGREGATE_ITEMS = 32;
    private static final int MAX_AGGREGATE_KEY_LENGTH = 128;
    private static final int MAX_AGGREGATE_STRING_LENGTH = 1_000;
    private static final int MAX_SOURCE_REFERENCE_LENGTH = 256;

    public ManualPlaybookRecordedEvidenceSeed {
        contractVersion = required(contractVersion, "contractVersion");
        if (!CONTRACT_VERSION.equals(contractVersion)) {
            throw new IllegalArgumentException("unsupported recorded evidence seed contract");
        }
        suiteId = required(suiteId, "suiteId");
        if (suiteVersion < 1) {
            throw new IllegalArgumentException("suiteVersion must be positive");
        }
        selectorKey = required(selectorKey, "selectorKey");
        String requiredRequestId = required(
                requiredEvidenceRequestId, "requiredEvidenceRequestId");
        requiredEvidenceRequestId = requiredRequestId;
        sourceReference = required(sourceReference, "sourceReference");
        requireSafeString(sourceReference, MAX_SOURCE_REFERENCE_LENGTH);
        if (exampleCandidate == null
                || !selectorKey.equals(exampleCandidate.routingKey())
                || !"candidate".equals(exampleCandidate.status())
                || exampleCandidate.verified()) {
            throw new IllegalArgumentException(
                    "recorded evidence seed requires an unverified candidate for its selector");
        }
        EvidenceRequest requiredRequest = exampleCandidate.evidenceRequests().stream()
                .filter(request -> requiredRequestId.equals(request.requestId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "required evidence request must belong to the candidate"));
        if (!requiredRequest.required()) {
            throw new IllegalArgumentException(
                    "recorded evidence seed request must be required");
        }
        if (positiveCase == null
                || positiveCase.cohort() != ManualPlaybookReplaySuite.Cohort.POSITIVE
                || positiveCase.expectedDisposition()
                != ManualPlaybookReplaySuite.Disposition.MATCHED) {
            throw new IllegalArgumentException(
                    "recorded evidence seed requires one matched positive case");
        }
        if (positiveCase.evidence().size() > MAX_AGGREGATE_ITEMS) {
            throw unsafeAggregate();
        }
        positiveCase.evidence().forEach(evidence ->
                requireSafeAggregate(evidence.observed(), 0));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static void requireSafeAggregate(Object value, int depth) {
        if (value == null || depth > MAX_AGGREGATE_DEPTH) {
            throw unsafeAggregate();
        }
        if (value instanceof String stringValue) {
            requireSafeString(stringValue, MAX_AGGREGATE_STRING_LENGTH);
            return;
        }
        if (value instanceof Double doubleValue && !Double.isFinite(doubleValue)
                || value instanceof Float floatValue && !Float.isFinite(floatValue)) {
            throw unsafeAggregate();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return;
        }
        if (value instanceof Map<?, ?> mapValue) {
            if (mapValue.size() > MAX_AGGREGATE_ITEMS) {
                throw unsafeAggregate();
            }
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                if (!(entry.getKey() instanceof String key)
                        || key.isBlank()
                        || key.length() > MAX_AGGREGATE_KEY_LENGTH
                        || !TroubleshootingSecretRedactor.redact(key + "=placeholder")
                        .equals(key + "=placeholder")) {
                    throw unsafeAggregate();
                }
                requireSafeAggregate(entry.getValue(), depth + 1);
            }
            return;
        }
        if (value instanceof List<?> listValue) {
            if (listValue.size() > MAX_AGGREGATE_ITEMS) {
                throw unsafeAggregate();
            }
            listValue.forEach(item -> requireSafeAggregate(item, depth + 1));
            return;
        }
        throw unsafeAggregate();
    }

    private static void requireSafeString(String value, int maxLength) {
        if (value.length() > maxLength
                || !TroubleshootingSecretRedactor.redact(value).equals(value)) {
            throw unsafeAggregate();
        }
    }

    private static IllegalArgumentException unsafeAggregate() {
        return new IllegalArgumentException(
                "recorded evidence seed must contain only safe bounded aggregate data");
    }
}
