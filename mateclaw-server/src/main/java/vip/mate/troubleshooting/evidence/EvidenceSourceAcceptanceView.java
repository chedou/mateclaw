package vip.mate.troubleshooting.evidence;

import java.util.List;

/**
 * 某个证据源当前的验收状态。
 *
 * <p><b>{@code STALE} 是这里最要紧的一档。</b> 它不是「过期了」，是「配置在验收
 * 之后被改过，那次验收不再指向同一件事」。把它和 {@code NOT_ACCEPTED} 混成一档，
 * 读者会以为只是还没做；把它和 {@code ACCEPTED} 混成一档，就等于让一次针对旧配置
 * 的验收替新配置背书。</p>
 */
public record EvidenceSourceAcceptanceView(
        Status status,
        String platform,
        String currentBindingFingerprint,
        EvidenceSourceAcceptance acceptance,
        List<String> blockers) {

    public EvidenceSourceAcceptanceView {
        status = status == null ? Status.BLOCKED : status;
        platform = platform == null ? "" : platform.trim();
        currentBindingFingerprint = currentBindingFingerprint == null
                ? null
                : currentBindingFingerprint.trim();
        blockers = List.copyOf(blockers == null ? List.of() : blockers);
        if (status == Status.ACCEPTED
                && (acceptance == null || currentBindingFingerprint == null
                        || !acceptance.bindingFingerprint().equals(currentBindingFingerprint))) {
            // 契约层面堵死「状态说 ACCEPTED，指纹却对不上」这种组合——
            // 它正是一次针对旧配置的验收替新配置背书的样子。
            throw new IllegalArgumentException(
                    "ACCEPTED requires an acceptance bound to the current fingerprint");
        }
    }

    /** 真源采样是否被授权。唯一可以据此放行的判断。 */
    public boolean acceptedForCurrentBinding() {
        return status == Status.ACCEPTED;
    }

    public enum Status {
        /** 绑定本身不可用（没配、配错），还谈不到验收。 */
        BLOCKED,
        /** 绑定可用，但没有人验收过。 */
        NOT_ACCEPTED,
        /** 验收过，但配置在那之后变了；那次验收不再指向同一件事。 */
        STALE,
        /** 已验收，且验收钉住的正是当前这份配置。 */
        ACCEPTED
    }
}
