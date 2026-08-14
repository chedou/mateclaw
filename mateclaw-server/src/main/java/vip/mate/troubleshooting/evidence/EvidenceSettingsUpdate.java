package vip.mate.troubleshooting.evidence;

/**
 * One owner-submitted change to a workspace's evidence settings.
 *
 * <p>{@code guanceApiKey} is three-valued on purpose, because "leave the key
 * alone" and "remove the key" are different intents and a plain nullable
 * string cannot express both:
 *
 * <ul>
 *   <li>{@code null} — keep whatever is stored. This is what the UI sends when
 *       the owner edits the URL without retyping a credential they cannot read
 *       back.</li>
 *   <li>{@code ""} — clear the stored credential.</li>
 *   <li>anything else — replace it.</li>
 * </ul>
 *
 * @param expectedVersion the version the owner read; a mismatch rejects the
 *                        write rather than clobbering a concurrent edit
 */
public record EvidenceSettingsUpdate(
        boolean guanceEnabled,
        String guanceBaseUrl,
        String guanceApiKey,
        boolean guanceAllowInsecureHttp,
        boolean replayEnabled,
        boolean agentEnabled,
        int expectedVersion,
        String changeReason) {
}
