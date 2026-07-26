package vip.mate.troubleshooting.card;

import java.util.Map;

/**
 * Button payload carried by a troubleshooting card.
 *
 * <p>Wire form is {@code ts.<verb>} plus the diagnosis id, kept deliberately
 * small: Feishu caps a button value, and a card that outgrows the cap simply
 * fails to send. Everything else the handler needs it reads from the stored
 * aggregate, so the button carries identifiers rather than state — a card that
 * has been sitting in a chat for an hour cannot replay stale values.</p>
 *
 * <p>The {@code ts.} prefix is what the dispatcher routes on, and it must stay
 * disjoint from every other card kind's prefix.</p>
 */
public record TroubleshootingCardAction(Verb verb, String diagnosisId) {

    /** Routing prefix owned by this domain. */
    public static final String ACTION_PREFIX = "ts.";

    private static final String KEY_ACTION = "action";
    private static final String KEY_DIAGNOSIS = "did";

    /**
     * Card verbs.
     *
     * <p>Only transitions that a chat client can express safely appear here.
     * Closing a case needs a structured outcome plus a summary, and approving a
     * production write needs a reviewed reason, so both stay in the console
     * where the operator sees the full evidence rather than a card summary.</p>
     */
    public enum Verb {
        /** Accept the machine's conclusion. */
        CONFIRM("confirm"),
        /** Open the case in the console; no state change. */
        OPEN("open");

        private final String wire;

        Verb(String wire) {
            this.wire = wire;
        }

        public String action() {
            return ACTION_PREFIX + wire;
        }

        static Verb fromAction(String action) {
            for (Verb verb : values()) {
                if (verb.action().equals(action)) {
                    return verb;
                }
            }
            return null;
        }
    }

    public TroubleshootingCardAction {
        if (verb == null) {
            throw new IllegalArgumentException("verb must not be null");
        }
        if (diagnosisId == null || diagnosisId.isBlank()) {
            throw new IllegalArgumentException("diagnosisId must not be blank");
        }
        diagnosisId = diagnosisId.trim();
    }

    /** Button value for an outbound card. */
    public Map<String, Object> encode() {
        return Map.of(KEY_ACTION, verb.action(), KEY_DIAGNOSIS, diagnosisId);
    }

    /**
     * Reads an inbound button value, or {@code null} when it is not ours or is
     * malformed. A caller treats {@code null} as "ignore this click" rather
     * than as an error, because a stale or hand-crafted payload must never
     * reach the lifecycle.
     */
    public static TroubleshootingCardAction decode(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        Verb verb = Verb.fromAction(text(value.get(KEY_ACTION)));
        String diagnosisId = text(value.get(KEY_DIAGNOSIS));
        if (verb == null || diagnosisId == null || diagnosisId.isBlank()) {
            return null;
        }
        return new TroubleshootingCardAction(verb, diagnosisId);
    }

    private static String text(Object raw) {
        return raw == null ? null : String.valueOf(raw).trim();
    }
}
