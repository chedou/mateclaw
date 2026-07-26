package vip.mate.troubleshooting.model;

/**
 * Why a signal did or did not contribute to the conclusion.
 *
 * <p>The engine treats "evaluated to false" and "could not be evaluated" the
 * same way — neither yields a signal — but for whoever is diagnosing they mean
 * opposite things. A criterion that ran and came out false has <em>ruled a
 * hypothesis out</em>. A criterion whose evidence never arrived has ruled
 * nothing out; the hypothesis is simply untested, and the next useful step is
 * to go collect that evidence. Collapsing the two would let an operator believe
 * a cause was excluded when it was never checked.</p>
 */
public enum CriterionOutcome {

    /** Evaluated true; the signal fired. */
    SATISFIED,

    /** Evaluated false on real observations — the hypothesis it guards is excluded. */
    EXCLUDED,

    /** Source evidence was missing, so the criterion never ran. Nothing is excluded. */
    UNEVALUATED
}
