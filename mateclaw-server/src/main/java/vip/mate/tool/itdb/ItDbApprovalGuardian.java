package vip.mate.tool.itdb;

import org.springframework.stereotype.Component;
import vip.mate.tool.guard.guardian.ToolGuardGuardian;
import vip.mate.tool.guard.model.GuardCategory;
import vip.mate.tool.guard.model.GuardDecision;
import vip.mate.tool.guard.model.GuardFinding;
import vip.mate.tool.guard.model.GuardSeverity;
import vip.mate.tool.guard.model.ToolInvocationContext;

import java.util.List;
import java.util.Map;

@Component
class ItDbApprovalGuardian implements ToolGuardGuardian {

    static final String APPROVAL_TOOL = "itdb_approve_sql_request";

    @Override
    public boolean supports(ToolInvocationContext context) {
        return context != null && APPROVAL_TOOL.equals(context.toolName());
    }

    @Override
    public List<GuardFinding> evaluate(ToolInvocationContext context) {
        if (!supports(context)) {
            return List.of();
        }
        return List.of(new GuardFinding(
                "itdb-single-ticket-approval",
                GuardSeverity.CRITICAL,
                GuardCategory.PRIVILEGE_ESCALATION,
                "ITDB SQL 工单审批需要确认",
                "该动作会推进一个外部 ITDB SQL 工单的审批节点。",
                "逐单核对工单号、完整 SQL、风险结论和 SQL SHA-256 后确认；确认不代表 SQL 已执行。",
                APPROVAL_TOOL,
                "ticketId",
                APPROVAL_TOOL,
                "single-ticket approval",
                GuardDecision.NEEDS_APPROVAL,
                Map.of("externalStateChange", true, "bulkApproval", false, "executesSql", false)));
    }

    @Override
    public int priority() {
        return 1_000;
    }

    @Override
    public boolean alwaysRun() {
        return false;
    }
}
