package vip.mate.troubleshooting.model;

/**
 * A recommended action. MANUAL_WRITE is structurally unable to become an
 * executable platform action: it remains BLOCKED after approval and records
 * external outcomes separately.
 */
public record RecommendedAction(
        String actionId,
        ActionType actionType,
        String title,
        String description,
        boolean requiresApproval,
        ApprovalStatus approvalStatus,
        ExecutionStatus executionStatus) {

    public RecommendedAction {
        actionId = required(actionId, "actionId");
        if (actionType == null) {
            throw new IllegalArgumentException("actionType must not be null");
        }
        title = required(title, "title");
        description = description == null ? "" : description;
        approvalStatus = approvalStatus == null ? ApprovalStatus.NOT_REQUIRED : approvalStatus;
        executionStatus = executionStatus == null ? ExecutionStatus.PENDING : executionStatus;
        if (actionType == ActionType.MANUAL_WRITE) {
            if (!requiresApproval || approvalStatus == ApprovalStatus.NOT_REQUIRED) {
                throw new IllegalArgumentException("manual writes require explicit human approval");
            }
            if (executionStatus != ExecutionStatus.BLOCKED) {
                throw new IllegalArgumentException("manual writes must remain BLOCKED in MateClaw");
            }
        }
    }

    public static RecommendedAction manualWrite(String actionId, String title, String description) {
        return new RecommendedAction(
                actionId,
                ActionType.MANUAL_WRITE,
                title,
                description,
                true,
                ApprovalStatus.PENDING,
                ExecutionStatus.BLOCKED);
    }

    public RecommendedAction approveWithoutExecution() {
        if (actionType != ActionType.MANUAL_WRITE) {
            throw new IllegalStateException("only manual writes use approval");
        }
        return new RecommendedAction(
                actionId,
                actionType,
                title,
                description,
                true,
                ApprovalStatus.APPROVED_NOT_EXECUTED,
                ExecutionStatus.BLOCKED);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
