package vip.mate.tool.itdb;

import java.util.List;

interface ItDbWorkflowGateway {

    List<ItDbPendingRequest> pendingRequests();

    ItDbTicket ticket(long workflowId);

    ItDbSqlCheck check(ItDbTicket ticket);

    List<ItDbWorkflowLog> logs(long workflowId);

    void approve(long workflowId, String remark);
}
