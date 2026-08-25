package vip.mate.tool.itdb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import vip.mate.tool.ConcurrencyUnsafe;
import vip.mate.tool.guard.service.ToolGuardConfigService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class ItDbWorkflowTool {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final ReentrantLock[] APPROVAL_LOCKS = createApprovalLocks();

    private final ItDbWorkflowGateway gateway;
    private final ItDbSqlReviewService reviewService;
    private final ObjectMapper objectMapper;
    private final ItDbWorkflowProperties properties;
    private final ToolGuardConfigService guardConfigService;

    public ItDbWorkflowTool(ItDbWorkflowGateway gateway,
                            ItDbSqlReviewService reviewService,
                            ObjectMapper objectMapper,
                            ItDbWorkflowProperties properties,
                            ToolGuardConfigService guardConfigService) {
        this.gateway = gateway;
        this.reviewService = reviewService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.guardConfigService = guardConfigService;
    }

    @Tool(name = "itdb_pending_sql_requests", description = """
            Read the current ITDB SQL approval queue for the configured reviewer. This is read-only.
            Always use this before reviewing or approving a ticket. Never infer approval authority
            from a detail URL alone. The result contains workflow IDs, current approval stage and requester.
            """)
    public String pendingSqlRequests() {
        try {
            requireConfigured();
            return json(Map.of(
                    "status", "ok",
                    "reviewer", properties.getUsername(),
                    "requests", gateway.pendingRequests()));
        } catch (ItDbWorkflowException e) {
            return error(e.code(), e.getMessage());
        }
    }

    @Tool(name = "itdb_review_sql_request", description = """
            Read and deterministically review one ITDB SQL approval ticket. This is read-only.
            It rechecks the live pending queue, fetches the complete SQL, runs ITDB sqlcheck, and returns
            riskLevel, recommendation, canSubmitApproval, canExecuteSql=false, evidence and sqlSha256.
            Use the returned sqlSha256 unchanged if a later single-ticket approval is requested.
            """)
    public String reviewSqlRequest(
            @ToolParam(description = "ITDB SQL workflow ID from the current pending queue") String ticketId) {
        try {
            requireConfigured();
            return json(buildReview(parseTicketId(ticketId)).payload());
        } catch (ItDbWorkflowException e) {
            return error(e.code(), e.getMessage());
        }
    }

    @Tool(name = ItDbApprovalGuardian.APPROVAL_TOOL, description = """
            Submit 'pass' for exactly one ITDB SQL approval ticket. This advances an approval node only;
            it never calls ITDB's execute endpoint and never means the SQL ran. The action is always gated
            by MateClaw's persisted human approval. Before writing, it rechecks the live pending queue,
            complete SQL, ITDB sqlcheck, deterministic low-risk decision, and expected SQL SHA-256.
            After writing, it verifies that the ticket left the current queue and reads the workflow log.
            Never use for bulk approval, DDL, warnings, unknown scope, missing backup, or non-low-risk SQL.
            """)
    @ConcurrencyUnsafe("advances one external ITDB approval node and must be serialized per ticket")
    public String approveSqlRequest(
            @ToolParam(description = "ITDB SQL workflow ID from the current pending queue") String ticketId,
            @ToolParam(description = "Exact 64-character sqlSha256 returned by the latest read-only review")
            String expectedSqlSha256,
            @ToolParam(description = "Short approval remark recorded in ITDB") String approvalRemark) {
        try {
            requireConfigured();
            if (!guardConfigService.isEnabled()) {
                throw new ItDbWorkflowException(
                        "ITDB_APPROVAL_GUARD_DISABLED",
                        "ITDB approval is disabled because mandatory human confirmation is unavailable");
            }
            long id = parseTicketId(ticketId);
            if (expectedSqlSha256 == null || !SHA_256.matcher(expectedSqlSha256.strip()).matches()) {
                throw new ItDbWorkflowException("INVALID_SQL_HASH", "A valid review sqlSha256 is required");
            }
            if (approvalRemark == null || approvalRemark.isBlank()) {
                throw new ItDbWorkflowException("REMARK_REQUIRED", "Approval remark is required");
            }
            if (approvalRemark.length() > 500) {
                throw new ItDbWorkflowException("REMARK_TOO_LONG", "Approval remark must be at most 500 characters");
            }

            ReentrantLock lock = APPROVAL_LOCKS[Math.floorMod(Long.hashCode(id), APPROVAL_LOCKS.length)];
            lock.lock();
            try {
                return approveUnderLock(id, expectedSqlSha256, approvalRemark.strip());
            } finally {
                lock.unlock();
            }
        } catch (ItDbWorkflowException e) {
            return error(e.code(), e.getMessage());
        }
    }

    private String approveUnderLock(long id, String expectedSqlSha256, String approvalRemark) {
            ReviewEnvelope fresh = buildReview(id);
            if (!MessageDigest.isEqual(
                    fresh.sqlSha256().getBytes(StandardCharsets.US_ASCII),
                    expectedSqlSha256.strip().toLowerCase().getBytes(StandardCharsets.US_ASCII))) {
                return blocked("SQL_CHANGED", "SQL changed after review; review the ticket again", fresh.payload());
            }
            if (!fresh.review().canSubmitApproval()) {
                return blocked("NOT_DIRECTLY_APPROVABLE",
                        "The live deterministic review does not allow direct approval", fresh.payload());
            }

            List<ItDbWorkflowLog> logsBefore = gateway.logs(id);
            try {
                gateway.approve(id, approvalRemark);
            } catch (ItDbWorkflowException e) {
                return verificationRequired(
                        "APPROVAL_STATE_UNKNOWN",
                        "ITDB approval response was not conclusive; verify the workflow log and pending queue manually, and do not retry automatically",
                        e.code());
            }

            List<ItDbPendingRequest> pendingAfter;
            List<ItDbWorkflowLog> logs;
            try {
                pendingAfter = gateway.pendingRequests();
                logs = gateway.logs(id);
            } catch (ItDbWorkflowException e) {
                return verificationRequired(
                        "APPROVAL_VERIFICATION_FAILED",
                        "The approval request returned, but its final state could not be verified; check ITDB manually and do not retry automatically",
                        e.code());
            }
            boolean workflowAdvanced = pendingAfter.stream().noneMatch(item -> item.workflowId() == id);
            List<ItDbWorkflowLog> newLogs = logs.stream()
                    .filter(log -> !logsBefore.contains(log))
                    .toList();
            boolean matchingApprovalLog = newLogs.stream()
                    .anyMatch(log -> matchesApprovalLog(log, approvalRemark));
            boolean independentlyVerified = workflowAdvanced && matchingApprovalLog;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", independentlyVerified ? "approved" : "verification_required");
            result.put("workflowId", id);
            result.put("workflowAdvanced", workflowAdvanced);
            result.put("newApprovalLogObserved", matchingApprovalLog);
            result.put("sqlExecuted", false);
            result.put("message", independentlyVerified
                    ? "ITDB approval node advanced; SQL execution was not requested"
                    : "ITDB returned from approval, but independent verification is incomplete; do not retry automatically");
            result.put("latestLogs", logs.stream().limit(5).toList());
            return json(result);
    }

    private static boolean matchesApprovalLog(ItDbWorkflowLog log, String approvalRemark) {
        String operation = ((log.operationType() == null ? "" : log.operationType()) + " "
                + (log.operationInfo() == null ? "" : log.operationInfo())).toLowerCase();
        boolean passOperation = operation.contains("审核通过") || operation.contains("审批通过")
                || operation.contains("approved") || operation.contains("pass");
        return passOperation && log.operationInfo() != null
                && log.operationInfo().contains(approvalRemark);
    }

    private static ReentrantLock[] createApprovalLocks() {
        ReentrantLock[] locks = new ReentrantLock[256];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    private ReviewEnvelope buildReview(long workflowId) {
        List<ItDbPendingRequest> pending = gateway.pendingRequests();
        ItDbPendingRequest pendingItem = pending.stream()
                .filter(item -> item.workflowId() == workflowId && item.workflowType() == 2)
                .findFirst()
                .orElse(null);
        ItDbTicket ticket = gateway.ticket(workflowId);
        if (ticket.workflowId() != workflowId) {
            throw new ItDbWorkflowException("TICKET_MISMATCH", "ITDB returned a different workflow ID");
        }
        if (pendingItem != null) {
            ticket = ticket.withCurrentAudit(pendingItem.currentAudit());
        }
        ItDbSqlCheck check = gateway.check(ticket);
        ItDbReview review = reviewService.review(ticket, check, pendingItem != null);
        String sha = sha256(ticket.sqlContent());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ok");
        payload.put("ticket", ticket);
        payload.put("platformCheck", check);
        payload.put("review", review);
        payload.put("sqlSha256", sha);
        payload.put("approvalMeaning", "advances_workflow_only");
        payload.put("executionRequested", false);
        return new ReviewEnvelope(ticket, check, review, sha, payload);
    }

    private void requireConfigured() {
        if (!properties.isEnabled()) {
            throw new ItDbWorkflowException("ITDB_DISABLED", "ITDB workflow integration is disabled");
        }
        if (!properties.configured()) {
            throw new ItDbWorkflowException("ITDB_CREDENTIALS_MISSING",
                    "ITDB reviewer credentials are not configured on the MateClaw server");
        }
        properties.validatedBaseUri();
    }

    private static long parseTicketId(String value) {
        try {
            long id = Long.parseLong(value == null ? "" : value.strip());
            if (id <= 0) {
                throw new NumberFormatException("non-positive");
            }
            return id;
        } catch (NumberFormatException e) {
            throw new ItDbWorkflowException("INVALID_TICKET_ID", "ITDB workflow ID must be a positive integer");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String blocked(String code, String message, Map<String, Object> freshReview) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "blocked");
        out.put("code", code);
        out.put("message", message);
        out.put("freshReview", freshReview);
        return json(out);
    }

    private String error(String code, String message) {
        return json(Map.of("status", "error", "code", code, "message", message));
    }

    private String verificationRequired(String code, String message, String causeCode) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "verification_required");
        result.put("code", code);
        result.put("message", message);
        result.put("causeCode", causeCode);
        result.put("sqlExecuted", false);
        return json(result);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ITDB tool result", e);
        }
    }

    private record ReviewEnvelope(
            ItDbTicket ticket,
            ItDbSqlCheck check,
            ItDbReview review,
            String sqlSha256,
            Map<String, Object> payload
    ) {
    }
}
