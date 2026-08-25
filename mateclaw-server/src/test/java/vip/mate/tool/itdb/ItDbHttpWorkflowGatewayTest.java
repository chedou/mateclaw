package vip.mate.tool.itdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItDbHttpWorkflowGatewayTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void authenticatesWithJwtAndParsesPaginatedPendingQueue() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> login = response(200,
                "{\"access\":\"access-token\",\"refresh\":\"refresh-token\"}");
        HttpResponse<String> pending = response(200, """
                {"count":1,"results":[{
                  "audit_id":35398,
                  "workflow_type":2,
                  "workflow_title":"test ticket",
                  "group_name":"test group",
                  "create_user_display":"requester",
                  "current_audit":"owner",
                  "current_status":0,
                  "create_time":"2026-08-25T17:00:00+08:00"
                }]}
                """);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(login, pending);

        ItDbHttpWorkflowGateway gateway = new ItDbHttpWorkflowGateway(properties(), objectMapper, client);

        ItDbPendingRequest result = gateway.pendingRequests().getFirst();

        assertEquals(35398L, result.workflowId());
        assertEquals(2, result.workflowType());
        assertEquals("owner", result.currentAudit());
    }

    @Test
    @SuppressWarnings("unchecked")
    void refusesAccessGatewayRedirectWithoutForwardingCredentials() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> redirect = response(307, "");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(redirect);
        ItDbHttpWorkflowGateway gateway = new ItDbHttpWorkflowGateway(properties(), objectMapper, client);

        ItDbWorkflowException error = assertThrows(
                ItDbWorkflowException.class, gateway::pendingRequests);

        assertEquals("ITDB_ACCESS_GATEWAY_REQUIRED", error.code());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsIncompleteSqlCheckInsteadOfDefaultingMissingAffectedRowsToZero() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> login = response(200,
                "{\"access\":\"access-token\",\"refresh\":\"refresh-token\"}");
        HttpResponse<String> incompleteCheck = response(200,
                "{\"checked\":\"True\",\"error_count\":0,\"warning_count\":0,\"syntax_type\":2}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(login, incompleteCheck);
        ItDbHttpWorkflowGateway gateway = new ItDbHttpWorkflowGateway(properties(), objectMapper, client);
        ItDbTicket ticket = new ItDbTicket(35398L, "ticket", "group", "requester",
                "test_db", 12L, 2, true, "workflow_manreviewing", "owner",
                "2099-08-25T18:00:00+08:00", "2099-08-25T19:00:00+08:00", 0, "",
                "UPDATE customer SET status = 2 WHERE id = 42");

        ItDbWorkflowException error = assertThrows(ItDbWorkflowException.class,
                () -> gateway.check(ticket));

        assertEquals("INVALID_SQLCHECK_RESPONSE", error.code());
    }

    private static ItDbWorkflowProperties properties() {
        ItDbWorkflowProperties properties = new ItDbWorkflowProperties();
        properties.setEnabled(true);
        properties.setUsername("reviewer");
        properties.setPassword("not-a-real-secret");
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }
}
