package vip.mate.troubleshooting;

import vip.mate.troubleshooting.model.EvidenceResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sanitizes canonical evidence and replaces unsafe identifiers before the
 * evidence can enter a model prompt or a persisted diagnosis.
 */
public final class TroubleshootingEvidenceSanitizer {

    private static final Pattern SAFE_EVIDENCE_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final String REMAPPED_ID_PREFIX = "supplied-redacted-";

    private TroubleshootingEvidenceSanitizer() {
    }

    /**
     * Redacts every evidence field and deterministically assigns a unique safe
     * id to any query id that contains a secret or violates the identifier
     * grammar. Existing safe ids are reserved before remapping so a generated
     * id cannot collide with a later caller-provided id.
     */
    public static List<EvidenceResult> sanitize(List<EvidenceResult> evidence) {
        List<EvidenceResult> sanitized = new ArrayList<>();
        for (EvidenceResult result : evidence == null
                ? List.<EvidenceResult>of() : evidence) {
            if (result == null) {
                throw new IllegalArgumentException("supplied evidence must not contain null");
            }
            sanitized.add(TroubleshootingSecretRedactor.redact(result));
        }

        Set<String> reservedSafeIds = new LinkedHashSet<>();
        for (EvidenceResult result : sanitized) {
            if (isSafeEvidenceId(result.queryId())
                    && !reservedSafeIds.add(result.queryId())) {
                throw duplicateId(result.queryId());
            }
        }

        Set<String> allocatedIds = new HashSet<>(reservedSafeIds);
        List<EvidenceResult> result = new ArrayList<>(sanitized.size());
        int redactedIndex = 1;
        for (EvidenceResult item : sanitized) {
            if (isSafeEvidenceId(item.queryId())) {
                result.add(item);
                continue;
            }
            String queryId;
            do {
                queryId = REMAPPED_ID_PREFIX + redactedIndex++;
            } while (!allocatedIds.add(queryId));
            result.add(withQueryId(item, queryId));
        }
        return List.copyOf(result);
    }

    public static boolean isSafeEvidenceId(String value) {
        if (value == null) {
            return false;
        }
        String candidate = value.trim();
        return SAFE_EVIDENCE_ID.matcher(candidate).matches()
                && candidate.equals(TroubleshootingSecretRedactor.redact(candidate));
    }

    private static EvidenceResult withQueryId(EvidenceResult result, String queryId) {
        return new EvidenceResult(
                queryId,
                result.namespace(),
                result.query(),
                result.status(),
                result.summary(),
                result.observed(),
                result.source(),
                result.collectedAt());
    }

    private static IllegalArgumentException duplicateId(String queryId) {
        return new IllegalArgumentException("duplicate evidence queryId: " + queryId);
    }
}
