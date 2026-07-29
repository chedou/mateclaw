package vip.mate.troubleshooting.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** Canonical SHA-256 identities shared by T8 capture and baseline replay. */
final class EvaluationKeys {

    static final String SAMPLE_CONTRACT_VERSION = "evaluation-sample/v2";

    private EvaluationKeys() {
    }

    static String sampleKey(
            long workspaceId,
            String diagnosisId,
            String scenarioKey,
            EvidenceEvaluationSample.SourcePlatform sourcePlatform,
            String searchTerm,
            String window,
            Instant occurredAt) {
        return sampleKey(
                workspaceId,
                diagnosisId,
                scenarioKey,
                sourcePlatform,
                searchTerm,
                window,
                occurredAt,
                1);
    }

    static String sampleKey(
            long workspaceId,
            String diagnosisId,
            String scenarioKey,
            EvidenceEvaluationSample.SourcePlatform sourcePlatform,
            String searchTerm,
            String window,
            Instant occurredAt,
            int captureRevision) {
        if (captureRevision < 1) {
            throw new IllegalArgumentException("captureRevision must be positive");
        }
        String identity = captureIdentityKey(
                workspaceId,
                diagnosisId,
                scenarioKey,
                sourcePlatform,
                searchTerm,
                window,
                occurredAt);
        // Preserve the deployed v2 revision-one identity. Later immutable
        // recaptures derive a new key without overwriting the frozen oracle.
        return captureRevision == 1
                ? identity
                : hash(identity + "\u001f" + captureRevision);
    }

    static String captureIdentityKey(
            long workspaceId,
            String diagnosisId,
            String scenarioKey,
            EvidenceEvaluationSample.SourcePlatform sourcePlatform,
            String searchTerm,
            String window,
            Instant occurredAt) {
        if (sourcePlatform == null) {
            throw new IllegalArgumentException("sourcePlatform is required");
        }
        return hash(SAMPLE_CONTRACT_VERSION
                + "\u001f" + workspaceId
                + "\u001f" + diagnosisId
                + "\u001f" + scenarioKey
                + "\u001f" + sourcePlatform.name()
                + "\u001f" + searchTerm
                + "\u001f" + window
                + "\u001f" + occurredAt);
    }

    static String baselineRunKey(
            EvidenceEvaluationSample sample,
            String modelConfigVersion,
            String contractVersion) {
        if (sample == null) {
            throw new IllegalArgumentException("sample is required");
        }
        return hash(sample.sampleId()
                + "\u001f" + sample.version()
                + "\u001f" + sample.modelInputHash()
                + "\u001f" + modelConfigVersion
                + "\u001f" + contractVersion);
    }

    static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
