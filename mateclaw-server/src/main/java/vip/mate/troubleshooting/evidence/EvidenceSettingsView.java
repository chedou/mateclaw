package vip.mate.troubleshooting.evidence;

/**
 * The browser-facing projection of one workspace's evidence settings.
 *
 * <p>Deliberately has no field that can carry the API key. The credential is
 * write-only across the API: an owner can set or clear it and can see whether
 * one is present and how it ends, but no request ever reads it back. Returning
 * it — even to the owner who typed it — would put a live credential into
 * browser memory, proxy logs and screen shares for no operational gain.
 *
 * @param guanceApiKeyMask   {@code null} when no key is stored, otherwise a
 *                           masked hint such as {@code ****a1b2}
 * @param version            echoed back on write for the optimistic lock
 * @param origin             {@code DEPLOYMENT} when this workspace has no row
 *                           and is still inheriting application.yml
 */
public record EvidenceSettingsView(
        long workspaceId,
        boolean guanceEnabled,
        String guanceBaseUrl,
        boolean guanceApiKeyPresent,
        String guanceApiKeyMask,
        boolean guanceAllowInsecureHttp,
        boolean replayEnabled,
        boolean agentEnabled,
        int version,
        String changedBy,
        String changeReason,
        EffectiveEvidenceSettings.Origin origin) {
}
