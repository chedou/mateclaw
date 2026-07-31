package vip.mate.troubleshooting.synthesis;

import java.util.List;

/** Bounded deterministic result; raw fixture evidence is deliberately excluded. */
public record ManualPlaybookReplayEvaluation(
        int positiveTotal,
        int positivePassed,
        int negativeOrAbstainTotal,
        int negativeOrAbstainPassed,
        List<String> failureCodes) {

    public ManualPlaybookReplayEvaluation {
        failureCodes = List.copyOf(failureCodes == null ? List.of() : failureCodes);
        if (positiveTotal < 1
                || negativeOrAbstainTotal < 1
                || positivePassed < 0
                || positivePassed > positiveTotal
                || negativeOrAbstainPassed < 0
                || negativeOrAbstainPassed > negativeOrAbstainTotal) {
            throw new IllegalArgumentException("manual replay counters are invalid");
        }
    }

    public boolean passed() {
        return failureCodes.isEmpty()
                && positivePassed == positiveTotal
                && negativeOrAbstainPassed == negativeOrAbstainTotal;
    }
}
