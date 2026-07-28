package vip.mate.troubleshooting.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import vip.mate.troubleshooting.model.ActionOutcomeStatus;
import vip.mate.troubleshooting.model.ClosureOutcome;

/**
 * Request bodies for the human-controlled lifecycle.
 *
 * <p>None of them carries an {@code actor}: the operator is taken from the
 * authenticated principal, so an audit trail cannot be forged by posting
 * someone else's name.</p>
 */
public final class LifecycleRequests {

    private LifecycleRequests() {}

    /** Hand-off to a team. The note is what saves the receiver from re-diagnosing. */
    public record Transfer(@NotBlank String targetTeam, @NotBlank String note) {}

    /**
     * Authorization for one manual write.
     *
     * <p>The reason is mandatory because this is the record an auditor reads to
     * judge whether authorizing a production change was sound.</p>
     */
    public record Approve(@NotBlank String reason) {}

    /**
     * What actually happened when a human ran the approved write outside MateClaw.
     *
     * <p>{@code recoveryVerified} is a separate claim from {@code outcome}: an
     * action can succeed without the incident recovering, and only a verified
     * recovery may later support a {@code RECOVERED} closure.</p>
     */
    public record RecordOutcome(
            @NotNull ActionOutcomeStatus outcome,
            @NotBlank String notes,
            boolean recoveryVerified) {}

    /**
     * Closure. {@code createKnowledgeCandidate} decides whether this case
     * sediments a knowledge candidate. Recording or publishing that candidate
     * is not an approval decision and never overwrites an approved SOP.
     */
    public record Close(
            @NotNull ClosureOutcome outcome,
            @NotBlank @Size(max = 500) String summary,
            boolean recoveryVerified,
            String sopFeedback,
            boolean createKnowledgeCandidate) {}
}
