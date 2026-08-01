package vip.mate.troubleshooting.synthesis;

import java.util.List;

/** Human-authored structural oracle used by the fixed P1 replay evaluation. */
public record ReferenceSolution(
        String referenceId,
        String scenarioKey,
        List<String> requiredStepIntents,
        List<String> forbiddenStepIntents,
        List<OrderingConstraint> orderingConstraints,
        List<String> requiredEvidenceKinds) {

    public ReferenceSolution {
        requiredStepIntents = List.copyOf(requiredStepIntents == null ? List.of() : requiredStepIntents);
        forbiddenStepIntents = List.copyOf(forbiddenStepIntents == null ? List.of() : forbiddenStepIntents);
        orderingConstraints = List.copyOf(orderingConstraints == null ? List.of() : orderingConstraints);
        requiredEvidenceKinds = List.copyOf(requiredEvidenceKinds == null ? List.of() : requiredEvidenceKinds);
    }

    public static ReferenceSolution messageSendFailure() {
        return new ReferenceSolution(
                "reference-message-send-failure/v1",
                "message_send_failed",
                List.of(
                        "locate_failed_request",
                        "trace_ps_id",
                        "compare_success_sample",
                        "confirm_session_state_conflict",
                        "verify_recovery"),
                List.of(
                        "restart_production",
                        "write_session_state",
                        "invent_error_code"),
                List.of(
                        new OrderingConstraint("locate_failed_request", "trace_ps_id"),
                        new OrderingConstraint("trace_ps_id", "compare_success_sample"),
                        new OrderingConstraint("compare_success_sample", "confirm_session_state_conflict"),
                        new OrderingConstraint("confirm_session_state_conflict", "verify_recovery")),
                List.of("log_search", "log_trace_bundle", "contrast_sample"));
    }

    public record OrderingConstraint(String beforeIntent, String afterIntent) {
    }
}
