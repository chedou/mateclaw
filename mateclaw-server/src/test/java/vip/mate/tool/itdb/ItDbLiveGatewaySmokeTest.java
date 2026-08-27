package vip.mate.tool.itdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnabledIfEnvironmentVariable(named = "MATECLAW_ITDB_LIVE_TEST", matches = "true")
class ItDbLiveGatewaySmokeTest {

    @Test
    void authenticatesDirectlyAndReadsPendingQueue() {
        ItDbWorkflowProperties properties = new ItDbWorkflowProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(requiredEnvironment("MATECLAW_ITDB_BASE_URL"));
        properties.setUsername(requiredEnvironment("MATECLAW_ITDB_USERNAME"));
        properties.setPassword(requiredEnvironment("MATECLAW_ITDB_PASSWORD"));
        properties.setAllowInsecureHttp(Boolean.parseBoolean(
                requiredEnvironment("MATECLAW_ITDB_ALLOW_INSECURE_HTTP")));

        List<ItDbPendingRequest> pending = new ItDbHttpWorkflowGateway(
                properties, new ObjectMapper()).pendingRequests();

        assertNotNull(pending);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the live ITDB smoke test");
        }
        return value;
    }
}
