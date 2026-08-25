package vip.mate.tool.itdb;

import org.junit.jupiter.api.Test;
import vip.mate.tool.guard.model.GuardDecision;
import vip.mate.tool.guard.model.GuardSeverity;
import vip.mate.tool.guard.model.ToolInvocationContext;
import vip.mate.tool.guard.engine.ToolGuardEngine;
import vip.mate.tool.guard.engine.ToolPolicyResolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItDbApprovalGuardianTest {

    private final ItDbApprovalGuardian guardian = new ItDbApprovalGuardian();

    @Test
    void approvalToolAlwaysRequiresPersistedHumanConfirmation() {
        ToolInvocationContext context = ToolInvocationContext.of(
                "itdb_approve_sql_request", "{\"ticketId\":\"35398\"}", "conv", "agent");

        assertTrue(guardian.supports(context));
        assertFalse(guardian.alwaysRun());
        assertEquals(GuardDecision.NEEDS_APPROVAL,
                guardian.evaluate(context).getFirst().decision());
        assertEquals(GuardSeverity.CRITICAL,
                guardian.evaluate(context).getFirst().severity());
    }

    @Test
    void readOnlyReviewToolIsNotIntercepted() {
        ToolInvocationContext context = ToolInvocationContext.of(
                "itdb_review_sql_request", "{\"ticketId\":\"35398\"}", "conv", "agent");

        assertFalse(guardian.supports(context));
        assertTrue(guardian.evaluate(context).isEmpty());
    }

    @Test
    void engineDoesNotApplyItDbApprovalFindingToUnrelatedTools() {
        ToolGuardEngine engine = new ToolGuardEngine(
                java.util.List.of(guardian), new ToolPolicyResolver());

        assertEquals(GuardDecision.ALLOW, engine.evaluate(ToolInvocationContext.of(
                "itdb_review_sql_request", "{\"ticketId\":\"35398\"}", "conv", "agent")).decision());
        assertEquals(GuardDecision.ALLOW, engine.evaluate(ToolInvocationContext.of(
                "execute_shell_command", "{\"command\":\"pwd\"}", "conv", "agent")).decision());
    }
}
