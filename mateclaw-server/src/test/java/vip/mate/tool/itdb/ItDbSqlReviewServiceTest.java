package vip.mate.tool.itdb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItDbSqlReviewServiceTest {

    private final ItDbSqlReviewService service = new ItDbSqlReviewService();

    @Test
    void boundedBackedUpDmlWithCleanPlatformCheckCanBeSubmittedButNeverExecuted() {
        ItDbTicket ticket = ticket(
                "UPDATE customer SET status = 2 WHERE id = 42",
                true,
                2,
                "workflow_manreviewing");
        ItDbSqlCheck check = new ItDbSqlCheck(false, "pass", "", "", 0, 0,
                false, 2, 1, "pass");

        ItDbReview review = service.review(ticket, check, true);

        assertEquals(ItDbRecommendation.APPROVE, review.recommendation());
        assertTrue(review.canSubmitApproval());
        assertFalse(review.canExecuteSql(), "审批推进流程，不能被描述为执行 SQL");
        assertEquals(ItDbRiskLevel.LOW, review.riskLevel());
    }

    @Test
    void updateWithoutWhereIsRejectedEvenWhenPlatformCheckPasses() {
        ItDbTicket ticket = ticket(
                "UPDATE customer SET status = 2",
                true,
                2,
                "workflow_manreviewing");
        ItDbSqlCheck check = new ItDbSqlCheck(false, "pass", "", "", 0, 0,
                false, 2, 1, "pass");

        ItDbReview review = service.review(ticket, check, true);

        assertEquals(ItDbRecommendation.REJECT, review.recommendation());
        assertFalse(review.canSubmitApproval());
        assertTrue(review.blockers().stream().anyMatch(reason -> reason.contains("WHERE")));
    }

    @Test
    void ddlRequiresManualReviewAndCannotUseDirectApprovalPath() {
        ItDbTicket ticket = ticket(
                "ALTER TABLE customer ADD COLUMN source VARCHAR(32)",
                true,
                1,
                "workflow_manreviewing");
        ItDbSqlCheck check = new ItDbSqlCheck(false, "pass", "", "", 0, 0,
                false, 1, 0, "pass");

        ItDbReview review = service.review(ticket, check, true);

        assertEquals(ItDbRecommendation.MANUAL_REVIEW, review.recommendation());
        assertEquals(ItDbRiskLevel.HIGH, review.riskLevel());
        assertFalse(review.canSubmitApproval());
    }

    @Test
    void ticketOutsideCurrentPendingListCannotBeApproved() {
        ItDbTicket ticket = ticket(
                "DELETE FROM customer WHERE id = 42",
                true,
                2,
                "workflow_manreviewing");
        ItDbSqlCheck check = new ItDbSqlCheck(false, "pass", "", "", 0, 0,
                false, 2, 1, "pass");

        ItDbReview review = service.review(ticket, check, false);

        assertFalse(review.canSubmitApproval());
        assertTrue(review.blockers().stream().anyMatch(reason -> reason.contains("待办")));
    }

    @Test
    void emptyJsonArraysFromPlatformAreNotTreatedAsWarningsOrErrors() {
        ItDbTicket ticket = ticket(
                "UPDATE customer SET status = 2 WHERE id = 42",
                true,
                2,
                "workflow_manreviewing");
        ItDbSqlCheck check = new ItDbSqlCheck(false, "pass", "[]", "[]", 0, 0,
                false, 2, 1, "pass");

        ItDbReview review = service.review(ticket, check, true);

        assertEquals(ItDbRecommendation.APPROVE, review.recommendation());
    }

    @Test
    void livePlatformTrueCheckedValueIsTreatedAsPassed() {
        ItDbTicket ticket = ticket(
                "UPDATE customer SET status = 2 WHERE id = 42",
                true,
                2,
                "workflow_manreviewing");
        ItDbSqlCheck check = new ItDbSqlCheck(false, null, "{}", "[]", 0, 0,
                false, 2, 0, "True");

        ItDbReview review = service.review(ticket, check, true);

        assertEquals(ItDbRecommendation.APPROVE, review.recommendation());
        assertTrue(review.canSubmitApproval());
        assertFalse(review.canExecuteSql());
    }

    @Test
    void nonKeyUpdatePredicateRequiresManualReview() {
        ItDbTicket ticket = ticket(
                "UPDATE customer SET status = 2 WHERE status = 1",
                true,
                2,
                "workflow_manreviewing");
        ItDbSqlCheck check = new ItDbSqlCheck(false, "True", "", "", 0, 0,
                false, 2, 1, null);

        ItDbReview review = service.review(ticket, check, true);

        assertEquals(ItDbRecommendation.MANUAL_REVIEW, review.recommendation());
        assertFalse(review.canSubmitApproval());
        assertTrue(review.residualRisks().stream().anyMatch(reason -> reason.contains("主键")));
    }

    @Test
    void insertSelectRequiresManualReview() {
        ItDbTicket ticket = ticket(
                "INSERT INTO customer_archive (id) SELECT id FROM customer WHERE status = 0",
                true,
                2,
                "workflow_manreviewing");
        ItDbSqlCheck check = new ItDbSqlCheck(false, "True", "", "", 0, 0,
                false, 2, 1, null);

        ItDbReview review = service.review(ticket, check, true);

        assertEquals(ItDbRecommendation.MANUAL_REVIEW, review.recommendation());
        assertFalse(review.canSubmitApproval());
    }

    @Test
    void multipleStatementsRequireManualReview() {
        ItDbTicket ticket = ticket(
                "UPDATE customer SET status = 2 WHERE id = 42; DELETE FROM audit_log WHERE id = 7",
                true,
                2,
                "workflow_manreviewing");
        ItDbSqlCheck check = new ItDbSqlCheck(false, "True", "", "", 0, 0,
                false, 2, 2, null);

        ItDbReview review = service.review(ticket, check, true);

        assertEquals(ItDbRecommendation.MANUAL_REVIEW, review.recommendation());
        assertFalse(review.canSubmitApproval());
        assertTrue(review.residualRisks().stream().anyMatch(reason -> reason.contains("多条")));
    }

    @Test
    void contradictoryPlatformStatusCannotBeDirectlyApproved() {
        ItDbTicket ticket = ticket(
                "UPDATE customer SET status = 2 WHERE id = 42",
                true,
                2,
                "workflow_manreviewing");
        ItDbSqlCheck check = new ItDbSqlCheck(false, "True", "", "", 0, 0,
                false, 2, 1, "failed");

        ItDbReview review = service.review(ticket, check, true);

        assertEquals(ItDbRecommendation.MANUAL_REVIEW, review.recommendation());
        assertFalse(review.canSubmitApproval());
        assertTrue(review.residualRisks().stream().anyMatch(reason -> reason.contains("检查结果")));
    }

    @Test
    void expiredExecutionWindowCannotBeDirectlyApproved() {
        ItDbTicket ticket = new ItDbTicket(35398L, "test ticket", "test group", "requester",
                "test_db", 12L, 2, true, "workflow_manreviewing", "owner",
                "2000-01-01T18:00:00+08:00", "2000-01-01T19:00:00+08:00", 0, "",
                "UPDATE customer SET status = 2 WHERE id = 42");
        ItDbSqlCheck check = new ItDbSqlCheck(false, "True", "", "", 0, 0,
                false, 2, 1, null);

        ItDbReview review = service.review(ticket, check, true);

        assertEquals(ItDbRecommendation.MANUAL_REVIEW, review.recommendation());
        assertFalse(review.canSubmitApproval());
        assertTrue(review.residualRisks().stream().anyMatch(reason -> reason.contains("时间窗口")));
    }

    @Test
    void updateJoinCannotUseRelatedTablesIdAsTargetBound() {
        ItDbTicket ticket = ticket(
                "UPDATE orders o JOIN users u ON o.user_id = u.id SET o.status = 2 WHERE u.id = 42",
                true,
                2,
                "workflow_manreviewing");
        ItDbSqlCheck check = new ItDbSqlCheck(false, "True", "", "", 0, 0,
                false, 2, 1, null);

        ItDbReview review = service.review(ticket, check, true);

        assertEquals(ItDbRecommendation.MANUAL_REVIEW, review.recommendation());
        assertFalse(review.canSubmitApproval());
        assertTrue(review.residualRisks().stream().anyMatch(reason -> reason.contains("JOIN")));
    }

    @Test
    void qualifiedIdMustBelongToUpdateTargetAlias() {
        ItDbTicket ticket = ticket(
                "UPDATE orders o SET o.status = 2 WHERE users.id = 42",
                true,
                2,
                "workflow_manreviewing");
        ItDbSqlCheck check = new ItDbSqlCheck(false, "True", "", "", 0, 0,
                false, 2, 1, null);

        ItDbReview review = service.review(ticket, check, true);

        assertEquals(ItDbRecommendation.MANUAL_REVIEW, review.recommendation());
        assertFalse(review.canSubmitApproval());
    }

    private static ItDbTicket ticket(String sql, boolean backup, int syntaxType, String status) {
        return new ItDbTicket(35398L, "test ticket", "test group", "requester",
                "test_db", 12L, syntaxType, backup, status, "owner",
                "2099-08-25T18:00:00+08:00", "2099-08-25T19:00:00+08:00", 0, "", sql);
    }
}
