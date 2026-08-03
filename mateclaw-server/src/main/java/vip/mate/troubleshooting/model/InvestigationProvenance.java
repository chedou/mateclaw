package vip.mate.troubleshooting.model;

import java.time.Instant;
import java.util.List;

/**
 * 这次调查动用了什么，以及**刻意没有动用什么**。
 *
 * <p><b>Why the negatives are first-class here.</b> This product's safety
 * argument is almost entirely a set of things that did not happen: the
 * deterministic path calls no model, the platform has no production-write
 * executor, evidence collection is read-only, and today's numbers come from a
 * fixture rather than a live source. Every one of those is enforced in code and
 * covered by tests — and none of them was stated anywhere a reviewer looks. A
 * page that lists only participants invites the reader to assume the rest, and
 * what they assume will be more generous than the truth.</p>
 *
 * <p><b>Why it is a separate read model.</b> The facts already exist, scattered
 * across four shapes: the Diagnosis aggregate, each EvidenceResult's source, the
 * frozen ApprovedPlaybookVersion, and the derivation projection. Asking a
 * reviewer to assemble those is asking them to trust that they assembled them
 * correctly. This does the assembly once, server-side, from the frozen record.</p>
 *
 * <p><b>It asserts nothing it cannot see.</b> Where a fact is unavailable — an
 * adapter that never answered, a Playbook version no longer readable — it says
 * so rather than omitting the row. An omitted participant reads as "nothing
 * happened there", which is the one thing provenance must never imply.</p>
 */
public record InvestigationProvenance(
        String diagnosisId,
        Knowledge knowledge,
        List<Collector> collectors,
        Reasoning reasoning,
        List<Abstention> abstentions) {

    public InvestigationProvenance {
        collectors = List.copyOf(collectors == null ? List.of() : collectors);
        abstentions = List.copyOf(abstentions == null ? List.of() : abstentions);
        if (diagnosisId == null || diagnosisId.isBlank()) {
            throw new IllegalArgumentException("diagnosisId is required");
        }
        if (reasoning == null) {
            throw new IllegalArgumentException("reasoning is required");
        }
        if (abstentions.isEmpty()) {
            // The negatives are the point. A provenance with none of them is a
            // participant list, and a participant list lets the reader assume
            // whatever it does not mention.
            throw new IllegalArgumentException(
                    "provenance must state what did not participate");
        }
    }

    /**
     * 指挥这次调查的那份知识，以及它本身可不可信。
     *
     * @param origin        how the Playbook came to exist, e.g. MANUAL_WRITE or
     *                      an induction record — 手写夹具和真实归纳在注册表里长得
     *                      一样，而在用它下结论的地方，这个区别最要紧
     * @param operational   whether it was approved, or merely a draft being
     *                      shadowed
     * @param readable      false when the frozen version can no longer be read;
     *                      the rest of this record is then unreliable and says so
     */
    public record Knowledge(
            String selectorKey,
            String title,
            String playbookId,
            Integer playbookVersion,
            String ownerTeam,
            String origin,
            boolean operational,
            boolean readable,
            String note) {
    }

    /**
     * 一条取证请求，和实际去问的那个适配器。
     *
     * @param adapter   the platform that answered, or the seam that reported it
     *                  could not — never blank, because "we don't know who we
     *                  asked" is itself the finding
     * @param answered  false for MISSING; kept as its own flag so a caller
     *                  cannot count attempts as answers
     * @param cited     null when this investigation keeps no citation list at
     *                  all. The error-code path does not populate one — citations
     *                  are required of the model path — so a plain {@code false}
     *                  there would render as "这条证据没有支撑结论", which is a
     *                  different and much worse claim than "本路径不维护引用清单".
     *                  Same discipline as EXCLUDED versus UNEVALUATED.
     */
    public record Collector(
            String requestId,
            String signalKind,
            String adapter,
            EvidenceStatus status,
            boolean answered,
            Boolean cited,
            Instant collectedAt) {

        public Collector {
            if (Boolean.TRUE.equals(cited) && !answered) {
                throw new IllegalArgumentException(
                        "evidence that never answered cannot be cited (A1)");
            }
        }
    }

    /**
     * 怎么从证据走到结论的。
     *
     * @param modelInvoked      whether a model participated at all. The
     *                          deterministic path is zero-LLM by construction,
     *                          and saying so is more useful than leaving it blank
     * @param modelIdentity     null when no model ran; naming it otherwise
     * @param signalsSatisfied  how many criteria actually held
     */
    public record Reasoning(
            RouteMode routeMode,
            InvestigationMode investigationMode,
            RouteAuthority routeAuthority,
            ConclusionType conclusionType,
            boolean modelInvoked,
            String modelIdentity,
            int signalsSatisfied,
            boolean derivationRebuildable) {

        public Reasoning {
            if (routeMode == null || investigationMode == null
                    || routeAuthority == null || conclusionType == null) {
                throw new IllegalArgumentException("route and conclusion facts are required");
            }
            if (signalsSatisfied < 0) {
                throw new IllegalArgumentException("signal count must not be negative");
            }
            if (modelInvoked == (modelIdentity == null)) {
                throw new IllegalArgumentException(
                        "a model that ran must be named, and one that did not must not be");
            }
        }
    }

    /**
     * 一件**没有发生**的事，以及它为什么没发生。
     *
     * @param capability what did not participate, in the reader's vocabulary
     * @param reason     why — a mechanism, not a reassurance
     */
    public record Abstention(String capability, String reason) {

        public Abstention {
            if (capability == null || capability.isBlank()
                    || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException(
                        "an unstated reason is not a claim a reviewer can check");
            }
        }
    }
}
