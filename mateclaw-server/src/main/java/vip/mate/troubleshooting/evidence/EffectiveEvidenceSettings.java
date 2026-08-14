package vip.mate.troubleshooting.evidence;

import java.util.function.Supplier;

/**
 * The evidence source settings actually in force for one workspace.
 *
 * <p>Resolved per call rather than frozen at startup, which is the point of
 * moving these out of yml: an owner can turn a source on and the next
 * diagnosis sees it, with no restart.
 *
 * <p>The credential is held as a supplier, not a string, and that is a security
 * property rather than a style choice. Most callers only want to know whether a
 * source is switched on or whether an endpoint is set; readiness inspection in
 * particular must report {@code NOT_INSPECTED} and reach no credential at all
 * until the asset scope has been authorized. If the key were resolved eagerly,
 * merely constructing this record would read it — decrypting a stored key, or
 * touching the yml value — and that ordering guarantee would be gone. The
 * supplier moves the read to whoever actually needs the key.
 *
 * <p>Once resolved, the value is plaintext: this type exists to be handed to
 * the adapter that is about to make the call. It must never be serialized into
 * a response, an event, a log line or an audit record. The browser-facing shape
 * is {@link EvidenceSettingsView}, which carries a mask instead.
 *
 * @param guanceApiKeySource resolves the credential on demand; may be
 *                           {@code null} when no credential can exist
 * @param origin             where these values came from, so the UI can tell an
 *                           owner whether they are looking at their own row or
 *                           at the deployment fallback they have not overridden
 */
public record EffectiveEvidenceSettings(
        boolean guanceEnabled,
        String guanceBaseUrl,
        Supplier<String> guanceApiKeySource,
        boolean guanceAllowInsecureHttp,
        boolean replayEnabled,
        boolean agentEnabled,
        Origin origin) {

    public enum Origin {
        /** No row for this workspace; application.yml still governs. */
        DEPLOYMENT,
        /** A workspace row exists and overrides the deployment defaults. */
        WORKSPACE
    }

    /** For callers that already hold the credential and need no laziness. */
    public static EffectiveEvidenceSettings resolved(
            boolean guanceEnabled,
            String guanceBaseUrl,
            String guanceApiKey,
            boolean guanceAllowInsecureHttp,
            boolean replayEnabled,
            boolean agentEnabled,
            Origin origin) {
        return new EffectiveEvidenceSettings(
                guanceEnabled, guanceBaseUrl, () -> guanceApiKey,
                guanceAllowInsecureHttp, replayEnabled, agentEnabled, origin);
    }

    /**
     * Resolves the credential. Calling this is the point at which the key is
     * read, so call it only when about to use or classify it.
     */
    public String guanceApiKey() {
        return guanceApiKeySource == null ? null : guanceApiKeySource.get();
    }

    /**
     * Whether Guance is both switched on and holds the values a call needs.
     *
     * <p>Reads the credential, so this is a call-path check, not a cheap one.
     */
    public boolean guanceCallable() {
        if (!guanceEnabled || guanceBaseUrl == null || guanceBaseUrl.isBlank()) {
            return false;
        }
        String key = guanceApiKey();
        return key != null && !key.isBlank();
    }
}
