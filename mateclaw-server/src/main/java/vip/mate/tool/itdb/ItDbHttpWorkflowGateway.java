package vip.mate.tool.itdb;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
class ItDbHttpWorkflowGateway implements ItDbWorkflowGateway {

    private static final int SQL_WORKFLOW_TYPE = 2;

    private final ItDbWorkflowProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Object tokenLock = new Object();

    private volatile String accessToken;
    private volatile String refreshToken;

    @Autowired
    ItDbHttpWorkflowGateway(ItDbWorkflowProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(nonNullDuration(properties.getConnectTimeout(), Duration.ofSeconds(5)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    ItDbHttpWorkflowGateway(ItDbWorkflowProperties properties,
                            ObjectMapper objectMapper,
                            HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public List<ItDbPendingRequest> pendingRequests() {
        JsonNode root = post("/api/v1/workflow/auditlist/",
                Map.of("engineer", properties.getUsername()), true);
        List<ItDbPendingRequest> result = new ArrayList<>();
        for (JsonNode item : rows(root)) {
            result.add(new ItDbPendingRequest(
                    longValue(item, "audit_id", "workflow_id", "id"),
                    intValue(item, "workflow_type"),
                    text(item, "workflow_title"),
                    text(item, "group_name"),
                    text(item, "create_user_display"),
                    text(item, "current_audit"),
                    intValue(item, "current_status"),
                    text(item, "create_time")));
        }
        return List.copyOf(result);
    }

    @Override
    public ItDbTicket ticket(long workflowId) {
        String query = "/api/v1/workflow/?workflow_id="
                + URLEncoder.encode(Long.toString(workflowId), StandardCharsets.UTF_8)
                + "&size=2";
        JsonNode root = get(query, true);
        JsonNode content = rows(root).stream()
                .filter(row -> longValue(row, "workflow_id", "id") == workflowId)
                .findFirst()
                .orElseThrow(() -> new ItDbWorkflowException("TICKET_NOT_FOUND",
                        "ITDB SQL workflow was not found"));
        JsonNode workflow = content.path("workflow");
        if (workflow.isMissingNode() || workflow.isNull()) {
            throw new ItDbWorkflowException("INVALID_TICKET_RESPONSE", "ITDB ticket response is incomplete");
        }
        return new ItDbTicket(
                longValue(content, "workflow_id", "id"),
                text(workflow, "workflow_name"),
                text(workflow, "group_name"),
                firstText(workflow, "engineer_display", "engineer"),
                text(workflow, "db_name"),
                longValue(workflow, "instance", "instance_id"),
                intValue(workflow, "syntax_type"),
                boolValue(workflow, "is_backup"),
                text(workflow, "status"),
                "",
                text(workflow, "run_date_start"),
                text(workflow, "run_date_end"),
                intValue(workflow, "is_manual"),
                text(workflow, "demand_url"),
                text(content, "sql_content"));
    }

    @Override
    public ItDbSqlCheck check(ItDbTicket ticket) {
        JsonNode node = post("/api/v1/workflow/sqlcheck/", Map.of(
                "instance_id", ticket.instanceId(),
                "db_name", ticket.database(),
                "full_sql", ticket.sqlContent()), true);
        if (!hasAnyNonNull(node, "checked", "status")
                || !hasAllNonNull(node, "warning_count", "error_count", "is_critical",
                "syntax_type", "affected_rows")) {
            throw new ItDbWorkflowException("INVALID_SQLCHECK_RESPONSE",
                    "ITDB SQL check response is incomplete");
        }
        return new ItDbSqlCheck(
                boolValue(node, "is_execute"),
                text(node, "checked"),
                text(node, "warning"),
                text(node, "error"),
                intValue(node, "warning_count"),
                intValue(node, "error_count"),
                boolValue(node, "is_critical"),
                intValue(node, "syntax_type"),
                longValue(node, "affected_rows"),
                text(node, "status"));
    }

    @Override
    public List<ItDbWorkflowLog> logs(long workflowId) {
        JsonNode root = post("/api/v1/workflow/log/", Map.of(
                "workflow_id", workflowId,
                "workflow_type", SQL_WORKFLOW_TYPE), true);
        List<ItDbWorkflowLog> result = new ArrayList<>();
        for (JsonNode item : rows(root)) {
            result.add(new ItDbWorkflowLog(
                    text(item, "operation_type_desc"),
                    text(item, "operation_info"),
                    text(item, "operator_display"),
                    text(item, "operation_time")));
        }
        return List.copyOf(result);
    }

    @Override
    public void approve(long workflowId, String remark) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("engineer", properties.getUsername());
        body.put("workflow_id", workflowId);
        body.put("audit_remark", remark);
        body.put("workflow_type", SQL_WORKFLOW_TYPE);
        body.put("audit_type", "pass");
        post("/api/v1/workflow/audit/", body, true);
    }

    private JsonNode get(String path, boolean retryOnUnauthorized) {
        return authorizedRequest("GET", path, null, retryOnUnauthorized);
    }

    private JsonNode post(String path, Object body, boolean retryOnUnauthorized) {
        return authorizedRequest("POST", path, body, retryOnUnauthorized);
    }

    private JsonNode authorizedRequest(String method, String path, Object body, boolean retryOnUnauthorized) {
        String token = accessToken();
        HttpResponse<String> response = send(method, path, body, token);
        if (response.statusCode() == 401 && retryOnUnauthorized) {
            clearAccessToken();
            token = accessToken();
            response = send(method, path, body, token);
        }
        return parseSuccessfulResponse(response);
    }

    private String accessToken() {
        String current = accessToken;
        if (current != null && !current.isBlank()) {
            return current;
        }
        synchronized (tokenLock) {
            if (accessToken != null && !accessToken.isBlank()) {
                return accessToken;
            }
            if (refreshToken != null && !refreshToken.isBlank()) {
                try {
                    JsonNode refreshed = unauthenticatedPost("/api/auth/token/refresh/",
                            Map.of("refresh", refreshToken));
                    accessToken = requiredText(refreshed, "access", "ITDB refresh response is incomplete");
                    return accessToken;
                } catch (ItDbWorkflowException ignored) {
                    refreshToken = null;
                }
            }
            JsonNode login = unauthenticatedPost("/api/auth/token/", Map.of(
                    "username", properties.getUsername(),
                    "password", properties.getPassword()));
            accessToken = requiredText(login, "access", "ITDB login response is incomplete");
            refreshToken = requiredText(login, "refresh", "ITDB login response is incomplete");
            return accessToken;
        }
    }

    private JsonNode unauthenticatedPost(String path, Object body) {
        return parseSuccessfulResponse(send("POST", path, body, null));
    }

    private HttpResponse<String> send(String method, String path, Object body, String token) {
        URI base = properties.validatedBaseUri();
        URI uri = URI.create(base + (path.startsWith("/") ? path : "/" + path));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(nonNullDuration(properties.getReadTimeout(), Duration.ofSeconds(20)))
                .header("Accept", "application/json")
                .header("User-Agent", "MateClaw-ITDB-Approval/1.0");
        String gatewayCookie = properties.validatedGatewayCookie();
        if (!gatewayCookie.isBlank()) {
            builder.header("Cookie", gatewayCookie);
        }
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(writeJson(body), StandardCharsets.UTF_8));
        }
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ItDbWorkflowException("ITDB_INTERRUPTED", "ITDB request was interrupted");
        } catch (IOException e) {
            throw new ItDbWorkflowException("ITDB_UNREACHABLE", "ITDB could not be reached from the MateClaw server");
        }
    }

    private JsonNode parseSuccessfulResponse(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            throw new ItDbWorkflowException("ITDB_ACCESS_GATEWAY_REQUIRED",
                    "ITDB redirected to the aTrust access gateway; configure a server-authorized API route or renew the configured gateway session");
        }
        if (status < 200 || status >= 300) {
            throw new ItDbWorkflowException("ITDB_HTTP_" + status,
                    "ITDB API returned HTTP " + status);
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new ItDbWorkflowException("ITDB_INVALID_JSON", "ITDB returned an invalid JSON response");
        }
    }

    private String writeJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ITDB request", e);
        }
    }

    private void clearAccessToken() {
        synchronized (tokenLock) {
            accessToken = null;
        }
    }

    private static List<JsonNode> rows(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return List.of();
        }
        JsonNode candidate = root;
        if (root.isObject() && root.path("results").isArray()) {
            candidate = root.path("results");
        } else if (root.isObject() && root.path("data").isArray()) {
            candidate = root.path("data");
        }
        if (candidate.isArray()) {
            List<JsonNode> values = new ArrayList<>();
            candidate.forEach(values::add);
            return values;
        }
        return candidate.isObject() ? List.of(candidate) : List.of();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? MissingNode.getInstance() : node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String requiredText(JsonNode node, String field, String message) {
        String value = text(node, field);
        if (value.isBlank()) {
            throw new ItDbWorkflowException("ITDB_AUTH_INVALID_RESPONSE", message);
        }
        return value;
    }

    private static long longValue(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node == null ? MissingNode.getInstance() : node.path(field);
            if (value.isIntegralNumber() || value.isTextual()) {
                try {
                    return value.asLong();
                } catch (RuntimeException ignored) {
                    // Try the next compatible field.
                }
            }
        }
        return 0L;
    }

    private static int intValue(JsonNode node, String field) {
        return node == null ? 0 : node.path(field).asInt(0);
    }

    private static boolean boolValue(JsonNode node, String field) {
        return node != null && node.path(field).asBoolean(false);
    }

    private static boolean hasAnyNonNull(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            return false;
        }
        for (String field : fields) {
            if (node.hasNonNull(field)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAllNonNull(JsonNode node, String... fields) {
        if (node == null || !node.isObject()) {
            return false;
        }
        for (String field : fields) {
            if (!node.hasNonNull(field)) {
                return false;
            }
        }
        return true;
    }

    private static Duration nonNullDuration(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
