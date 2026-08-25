package vip.mate.tool.itdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.tool.guard.service.ToolGuardConfigService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItDbWorkflowToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FakeGateway gateway;
    private ItDbWorkflowTool tool;

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        ItDbWorkflowProperties properties = new ItDbWorkflowProperties();
        properties.setEnabled(true);
        properties.setUsername("reviewer");
        properties.setPassword("not-a-real-secret");
        ToolGuardConfigService guardConfigService = mock(ToolGuardConfigService.class);
        when(guardConfigService.isEnabled()).thenReturn(true);
        tool = new ItDbWorkflowTool(gateway, new ItDbSqlReviewService(), objectMapper, properties,
                guardConfigService);
    }

    @Test
    void reviewReturnsFullEvidenceHashAndSeparatesApprovalFromExecution() throws Exception {
        JsonNode result = objectMapper.readTree(tool.reviewSqlRequest("35398"));

        assertEquals("ok", result.path("status").asText());
        assertEquals("35398", result.path("ticket").path("workflowId").asText());
        assertTrue(result.path("ticket").path("sqlContent").asText().contains("WHERE id = 42"));
        assertEquals(64, result.path("sqlSha256").asText().length());
        assertTrue(result.path("review").path("canSubmitApproval").asBoolean());
        assertTrue(!result.path("review").path("canExecuteSql").asBoolean());
    }

    @Test
    void approvalRechecksHashAndPendingStateThenVerifiesWorkflowAdvanced() throws Exception {
        String reviewJson = tool.reviewSqlRequest("35398");
        String hash = objectMapper.readTree(reviewJson).path("sqlSha256").asText();

        JsonNode result = objectMapper.readTree(
                tool.approveSqlRequest("35398", hash, "低风险审核通过"));

        assertEquals("approved", result.path("status").asText());
        assertTrue(result.path("workflowAdvanced").asBoolean());
        assertTrue(!result.path("sqlExecuted").asBoolean());
        assertEquals(1, gateway.approvalCalls.get());
    }

    @Test
    void changedSqlBlocksApprovalWithoutCallingWriteEndpoint() throws Exception {
        JsonNode result = objectMapper.readTree(
                tool.approveSqlRequest("35398", "0".repeat(64), "低风险审核通过"));

        assertEquals("blocked", result.path("status").asText());
        assertEquals("SQL_CHANGED", result.path("code").asText());
        assertEquals(0, gateway.approvalCalls.get());
    }

    @Test
    void ambiguousApprovalFailureRequiresVerificationAndMustNotInviteRetry() throws Exception {
        String hash = objectMapper.readTree(tool.reviewSqlRequest("35398")).path("sqlSha256").asText();
        gateway.failApproval = true;

        JsonNode result = objectMapper.readTree(
                tool.approveSqlRequest("35398", hash, "低风险审核通过"));

        assertEquals("verification_required", result.path("status").asText());
        assertEquals("APPROVAL_STATE_UNKNOWN", result.path("code").asText());
        assertTrue(result.path("message").asText().contains("do not retry"));
        assertTrue(!result.path("sqlExecuted").asBoolean());
        assertEquals(1, gateway.approvalCalls.get());
    }

    @Test
    void failedPostApprovalVerificationRequiresManualVerification() throws Exception {
        String hash = objectMapper.readTree(tool.reviewSqlRequest("35398")).path("sqlSha256").asText();
        gateway.failVerification = true;

        JsonNode result = objectMapper.readTree(
                tool.approveSqlRequest("35398", hash, "低风险审核通过"));

        assertEquals("verification_required", result.path("status").asText());
        assertEquals("APPROVAL_VERIFICATION_FAILED", result.path("code").asText());
        assertTrue(result.path("message").asText().contains("do not retry"));
        assertEquals(1, gateway.approvalCalls.get());
    }

    @Test
    void disabledGlobalToolGuardFailsClosedBeforeApprovalWrite() throws Exception {
        ItDbWorkflowProperties properties = new ItDbWorkflowProperties();
        properties.setEnabled(true);
        properties.setUsername("reviewer");
        properties.setPassword("not-a-real-secret");
        ToolGuardConfigService disabledGuard = mock(ToolGuardConfigService.class);
        when(disabledGuard.isEnabled()).thenReturn(false);
        ItDbWorkflowTool guardedTool = new ItDbWorkflowTool(
                gateway, new ItDbSqlReviewService(), objectMapper, properties, disabledGuard);
        String hash = objectMapper.readTree(guardedTool.reviewSqlRequest("35398"))
                .path("sqlSha256").asText();

        JsonNode result = objectMapper.readTree(
                guardedTool.approveSqlRequest("35398", hash, "低风险审核通过"));

        assertEquals("error", result.path("status").asText());
        assertEquals("ITDB_APPROVAL_GUARD_DISABLED", result.path("code").asText());
        assertEquals(0, gateway.approvalCalls.get());
    }

    @Test
    void oldWorkflowLogCannotVerifyThisApproval() throws Exception {
        String hash = objectMapper.readTree(tool.reviewSqlRequest("35398")).path("sqlSha256").asText();
        gateway.onlyPreexistingLog = true;

        JsonNode result = objectMapper.readTree(
                tool.approveSqlRequest("35398", hash, "低风险审核通过"));

        assertEquals("verification_required", result.path("status").asText());
        assertEquals(1, gateway.approvalCalls.get());
    }

    @Test
    void concurrentConfirmationsSubmitAtMostOncePerTicket() throws Exception {
        String hash = objectMapper.readTree(tool.reviewSqlRequest("35398")).path("sqlSha256").asText();
        gateway.approvalDelayMillis = 150;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = executor.submit(() -> {
                start.await();
                return tool.approveSqlRequest("35398", hash, "低风险审核通过");
            });
            Future<String> second = executor.submit(() -> {
                start.await();
                return tool.approveSqlRequest("35398", hash, "低风险审核通过");
            });
            start.countDown();
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, gateway.approvalCalls.get());
    }

    private static final class FakeGateway implements ItDbWorkflowGateway {
        private final AtomicInteger approvalCalls = new AtomicInteger();
        private boolean pending = true;
        private boolean failApproval;
        private boolean failVerification;
        private boolean onlyPreexistingLog;
        private long approvalDelayMillis;

        private final ItDbTicket ticket = new ItDbTicket(
                35398L,
                "test ticket",
                "test group",
                "requester",
                "test_db",
                12L,
                2,
                true,
                "workflow_manreviewing",
                "owner",
                "2099-08-25T18:00:00+08:00",
                "2099-08-25T19:00:00+08:00",
                0,
                "",
                "UPDATE customer SET status = 2 WHERE id = 42");

        @Override
        public List<ItDbPendingRequest> pendingRequests() {
            if (!pending && failVerification) {
                throw new ItDbWorkflowException("ITDB_UNREACHABLE", "verification unavailable");
            }
            return pending
                    ? List.of(new ItDbPendingRequest(35398L, 2, "test ticket", "test group",
                    "requester", "owner", 0, "2026-08-25T17:00:00+08:00"))
                    : List.of();
        }

        @Override
        public ItDbTicket ticket(long workflowId) {
            return ticket;
        }

        @Override
        public ItDbSqlCheck check(ItDbTicket ignored) {
            return new ItDbSqlCheck(false, "pass", "", "", 0, 0,
                    false, 2, 1, "pass");
        }

        @Override
        public List<ItDbWorkflowLog> logs(long workflowId) {
            List<ItDbWorkflowLog> out = new ArrayList<>();
            if (onlyPreexistingLog) {
                out.add(new ItDbWorkflowLog("审核通过", "历史审批", "another-reviewer",
                        "2026-08-24T17:01:00+08:00"));
            } else if (!pending) {
                out.add(new ItDbWorkflowLog("审核通过", "低风险审核通过", "reviewer",
                        "2026-08-25T17:01:00+08:00"));
            }
            return out;
        }

        @Override
        public void approve(long workflowId, String remark) {
            approvalCalls.incrementAndGet();
            if (approvalDelayMillis > 0) {
                try {
                    Thread.sleep(approvalDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ItDbWorkflowException("INTERRUPTED", "approval interrupted");
                }
            }
            if (failApproval) {
                throw new ItDbWorkflowException("ITDB_UNREACHABLE", "approval result unavailable");
            }
            pending = false;
        }
    }
}
