package vip.mate.troubleshooting.intake;

/**
 * Stable intake source identifiers across channel and Web conversation entries.
 *
 * <p>Both paths reuse {@link TroubleshootingIntakeSessionService} and converge
 * on the same Diagnosis. Web conversation reports synchronously at the HTTP
 * boundary, so it must not enqueue the channel delivery worker.</p>
 */
public final class TroubleshootingIntakeSources {

    public static final String WECOM = "wecom";
    /** Authenticated Web workbench multi-turn intake (demo / daily alternate entry). */
    public static final String WEB_CONVERSATION = "web:conversation";

    private TroubleshootingIntakeSources() {
    }

    public static boolean isLocalSynchronous(String source) {
        return WEB_CONVERSATION.equals(source);
    }
}
