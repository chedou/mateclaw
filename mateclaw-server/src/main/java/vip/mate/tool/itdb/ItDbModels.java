package vip.mate.tool.itdb;

import java.util.List;

record ItDbPendingRequest(
        long workflowId,
        int workflowType,
        String title,
        String groupName,
        String requester,
        String currentAudit,
        int currentStatus,
        String createTime
) {
}

record ItDbTicket(
        long workflowId,
        String title,
        String groupName,
        String requester,
        String database,
        long instanceId,
        int syntaxType,
        boolean backup,
        String status,
        String currentAudit,
        String runDateStart,
        String runDateEnd,
        int manualMode,
        String demandUrl,
        String sqlContent
) {
    ItDbTicket withCurrentAudit(String value) {
        return new ItDbTicket(workflowId, title, groupName, requester, database, instanceId,
                syntaxType, backup, status, value, runDateStart, runDateEnd, manualMode,
                demandUrl, sqlContent);
    }
}

record ItDbSqlCheck(
        boolean executable,
        String checked,
        String warning,
        String error,
        int warningCount,
        int errorCount,
        boolean critical,
        int syntaxType,
        long affectedRows,
        String status
) {
}

record ItDbWorkflowLog(
        String operationType,
        String operationInfo,
        String operator,
        String operationTime
) {
}

enum ItDbRecommendation {
    APPROVE,
    MANUAL_REVIEW,
    REJECT
}

enum ItDbRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

record ItDbReview(
        ItDbRiskLevel riskLevel,
        ItDbRecommendation recommendation,
        boolean canSubmitApproval,
        boolean canExecuteSql,
        long affectedRows,
        List<String> blockers,
        List<String> residualRisks,
        List<String> evidence
) {
}
