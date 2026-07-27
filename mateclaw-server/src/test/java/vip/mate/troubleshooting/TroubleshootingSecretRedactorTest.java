package vip.mate.troubleshooting;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class TroubleshootingSecretRedactorTest {

    @Test
    void fullyRedactsQuotedEscapedAndCookieSecrets() {
        String input = "{\"password\":\"correct horse battery staple\"} "
                + "{\"dbPassword\":\"database secret phrase\","
                + "\"authToken\":\"prefixed-token-value\"} "
                + "payload=\\{\\\"token\\\":\\\"escaped secret value\\\"\\} "
                + "Cookie: sid=secret1; auth=secret2";

        assertThat(TroubleshootingSecretRedactor.redact(input))
                .contains(TroubleshootingSecretRedactor.REDACTED)
                .doesNotContain(
                        "correct horse battery staple",
                        "horse battery staple",
                        "database secret phrase",
                        "prefixed-token-value",
                        "escaped secret value",
                        "secret1",
                        "secret2");
    }

    @Test
    void fullyRedactsContainerValuesAndLeavesLookalikeKeysAlone() {
        String input = "{\"password\":[\"one\",\"two\"],\"safe\":\"ok\"} "
                + "{\"credential\":{\"user\":\"alice\",\"value\":\"hidden\"}} "
                + "{\"credentials\":\"plural-secret\"} "
                + "{\"authorizationX\":\"visible\"}";

        assertThat(TroubleshootingSecretRedactor.redact(input))
                .contains("\"safe\":\"ok\"")
                .contains("\"authorizationX\":\"visible\"")
                .doesNotContain("\"one\"", "\"two\"", "alice", "hidden", "plural-secret");
    }

    @Test
    void boundsJwtScanningForLongNonJwtInput() {
        String adversarial = "eyJ".repeat(16_000);

        assertTimeout(Duration.ofSeconds(2), () ->
                assertThat(TroubleshootingSecretRedactor.redact(adversarial))
                        .isEqualTo(adversarial));
    }

    @Test
    void boundsSanitizedMapKeyCollisionHandling() {
        Map<String, Object> observed = new LinkedHashMap<>();
        for (int index = 0; index < 20_000; index++) {
            observed.put("Bearer collision-secret-" + index, index);
        }
        EvidenceResult evidence = new EvidenceResult(
                "safe-id", "L", "safe query", EvidenceStatus.ANOMALY, "summary",
                observed, "source", Instant.EPOCH);

        assertTimeout(Duration.ofSeconds(2), () ->
                assertThat(TroubleshootingSecretRedactor.redact(evidence).observed())
                        .hasSize(observed.size()));
    }

    @Test
    void redactsAWellFormedJwtWithoutMatchingEmbeddedBase64Text() {
        String jwt = "eyJabcdefgh.abcdefghijk.abcdefgh";
        String embedded = "prefix" + jwt;

        assertThat(TroubleshootingSecretRedactor.redact("Bearer " + jwt))
                .isEqualTo("Bearer " + TroubleshootingSecretRedactor.REDACTED);
        assertThat(TroubleshootingSecretRedactor.redact(embedded))
                .isEqualTo(embedded);
    }

    @Test
    void redactsAnOversizedStandaloneJwtWithoutUnboundedBacktracking() {
        String jwt = "eyJ" + "a".repeat(16)
                + "." + "b".repeat(8_193)
                + "." + "c".repeat(16);

        assertTimeout(Duration.ofSeconds(2), () ->
                assertThat(TroubleshootingSecretRedactor.redact(jwt))
                        .isEqualTo(TroubleshootingSecretRedactor.REDACTED));
    }

    @Test
    void redactsAValidJwtWithAShortPayloadSegment() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.e30."
                + "MhshWfd5X6VOY7kFYifb4xCWuoxqVGrcGIBYnku6Hd4";

        assertThat(TroubleshootingSecretRedactor.redact(jwt))
                .isEqualTo(TroubleshootingSecretRedactor.REDACTED);
    }

    @Test
    void redactsUnknownAuthorizationSchemesThroughEndOfLine() {
        String input = "Authorization: Token TOP_SECRET\n"
                + "Authorization: Digest username=alice response=DIGEST_SECRET\n"
                + "safe-marker";

        assertThat(TroubleshootingSecretRedactor.redact(input))
                .contains("safe-marker")
                .doesNotContain("TOP_SECRET", "alice", "DIGEST_SECRET");
    }

    @Test
    void redactsEveryDeclaredSecretKeyInStructuredEvidence() {
        EvidenceResult evidence = new EvidenceResult(
                "safe-id", "L", "safe query", EvidenceStatus.ANOMALY, "summary",
                Map.of(
                        "credential", "credential-value",
                        "cookie", "cookie-value",
                        "private_key", "private-key-value",
                        "secret_key", "snake-secret-value",
                        "secretAccessKey", "camel-secret-value"),
                "source", Instant.EPOCH);

        assertThat(TroubleshootingSecretRedactor.redact(evidence).observed().values())
                .containsOnly(TroubleshootingSecretRedactor.REDACTED);
    }

    @Test
    void redactsSecretKeyAliasesInRawEvidence() {
        String input = "{\"secret_key\":\"snake-secret-value\","
                + "\"secretAccessKey\":\"camel-secret-value\","
                + "\"klingAccessKey\":\"kling-access-value\","
                + "\"access_key_id\":\"aws-access-id\"}";

        assertThat(TroubleshootingSecretRedactor.redact(input))
                .contains(TroubleshootingSecretRedactor.REDACTED)
                .doesNotContain(
                        "snake-secret-value",
                        "camel-secret-value",
                        "kling-access-value",
                        "aws-access-id");
    }

    @Test
    void redactsAccessKeyAliasesInRecursiveStructuredEvidence() {
        EvidenceResult evidence = new EvidenceResult(
                "safe-id", "L", "safe query", EvidenceStatus.ANOMALY, "summary",
                Map.of("nested", Map.of(
                        "klingAccessKey", "kling-access-value",
                        "access_key_id", "aws-access-id")),
                "source", Instant.EPOCH);

        assertThat(evidence.observed()).containsKey("nested");
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) TroubleshootingSecretRedactor
                .redact(evidence).observed().get("nested");
        assertThat(nested.values())
                .containsOnly(TroubleshootingSecretRedactor.REDACTED);
    }

    @Test
    void redactsTheWholeEscapedValueWhenItContainsAnEscapedQuote() {
        String escapedQuote = "\\" + "\"";
        String nestedEscapedQuote = "\\".repeat(3) + "\"";
        String input = "payload=\\{" + escapedQuote + "password" + escapedQuote
                + ":" + escapedQuote + "abc" + nestedEscapedQuote
                + "def SECRET_TAIL" + escapedQuote + "\\} safe-marker";

        assertThat(TroubleshootingSecretRedactor.redact(input))
                .contains(TroubleshootingSecretRedactor.REDACTED, "safe-marker")
                .doesNotContain("abc", "def SECRET_TAIL", "SECRET_TAIL");
    }

    @Test
    void redactsTwiceSerializedJsonIncludingAnEmbeddedEscapedQuote() {
        String encodedQuote = "\\".repeat(3) + "\"";
        String encodedEscapedQuote = "\\".repeat(7) + "\"";
        String input = encodedQuote + "token" + encodedQuote + ":" + encodedQuote
                + "abc" + encodedEscapedQuote + "def TWO_LAYER_SECRET"
                + encodedQuote + " safe-marker";

        assertThat(TroubleshootingSecretRedactor.redact(input))
                .contains(TroubleshootingSecretRedactor.REDACTED, "safe-marker")
                .doesNotContain("abc", "def TWO_LAYER_SECRET", "TWO_LAYER_SECRET");
    }

    @Test
    void redactsEscapedContainerWhenAStringContainsQuoteAndClosingBrace() {
        String encodedQuote = "\\" + "\"";
        String encodedEscapedQuote = "\\".repeat(3) + "\"";
        String input = encodedQuote + "password" + encodedQuote + ":{"
                + encodedQuote + "nested" + encodedQuote + ":" + encodedQuote
                + "abc" + encodedEscapedQuote + "} LEAK_TAIL" + encodedQuote + ","
                + encodedQuote + "other" + encodedQuote + ":" + encodedQuote
                + "SECRET2" + encodedQuote + "} safe-marker";

        assertThat(TroubleshootingSecretRedactor.redact(input))
                .contains(TroubleshootingSecretRedactor.REDACTED, "safe-marker")
                .doesNotContain("abc", "LEAK_TAIL", "SECRET2");
    }
}
