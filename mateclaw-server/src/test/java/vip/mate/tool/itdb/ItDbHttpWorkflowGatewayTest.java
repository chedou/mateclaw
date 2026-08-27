package vip.mate.tool.itdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.net.Proxy;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

        ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(2)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest loginRequest = requests.getAllValues().get(0);
        assertEquals(URI.create("http://itdb.sangfor.com/api/auth/token/"), loginRequest.uri());
        assertEquals("POST", loginRequest.method());
        assertFalse(loginRequest.headers().firstValue("Authorization").isPresent());
        assertFalse(loginRequest.headers().firstValue("Cookie").isPresent());
        String loginBody = body(loginRequest);
        assertTrue(loginBody.contains("\"username\":\"reviewer\""));
        assertTrue(loginBody.contains("\"password\":\"not-a-real-secret\""));

        HttpRequest pendingRequest = requests.getAllValues().get(1);
        assertEquals(URI.create("http://itdb.sangfor.com/api/v1/workflow/auditlist/"), pendingRequest.uri());
        assertEquals("Bearer access-token", pendingRequest.headers().firstValue("Authorization").orElseThrow());
        assertFalse(pendingRequest.headers().firstValue("Cookie").isPresent());
    }

    @Test
    @SuppressWarnings("unchecked")
    void refusesUnexpectedRedirectWithoutForwardingCredentials() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<String> redirect = response(307, "");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(redirect);
        ItDbHttpWorkflowGateway gateway = new ItDbHttpWorkflowGateway(properties(), objectMapper, client);

        ItDbWorkflowException error = assertThrows(
                ItDbWorkflowException.class, gateway::pendingRequests);

        assertEquals("ITDB_REDIRECT_REFUSED", error.code());
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(1)).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals(URI.create("http://itdb.sangfor.com/api/auth/token/"), request.getValue().uri());
        assertFalse(request.getValue().headers().firstValue("Authorization").isPresent());
        assertFalse(request.getValue().headers().firstValue("Cookie").isPresent());
    }

    @Test
    void directClientBypassesSystemProxyPinsHttp11AndRefusesRedirects() {
        HttpClient client = ItDbHttpWorkflowGateway.directHttpClient(properties());

        assertEquals(HttpClient.Version.HTTP_1_1, client.version());
        assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
        List<Proxy> proxies = client.proxy().orElseThrow()
                .select(URI.create("http://itdb.sangfor.com/api/auth/token/"));
        assertEquals(List.of(Proxy.NO_PROXY), proxies);
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
        properties.setAllowInsecureHttp(true);
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private static String body(HttpRequest request) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompletableFuture<String> completed = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                completed.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                completed.complete(output.toString(StandardCharsets.UTF_8));
            }
        });
        return completed.get(1, TimeUnit.SECONDS);
    }
}
