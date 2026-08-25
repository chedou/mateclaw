package vip.mate.tool.itdb;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItDbWorkflowPropertiesTest {

    @Test
    void defaultEndpointUsesHttpsAndExactAllowedHost() {
        ItDbWorkflowProperties properties = new ItDbWorkflowProperties();

        assertEquals("https://itdb.atrust.sangfor.com", properties.validatedBaseUri().toString());
    }

    @Test
    void rejectsHttpAndHostsOutsideDeploymentAllowlist() {
        ItDbWorkflowProperties properties = new ItDbWorkflowProperties();
        properties.setBaseUrl("http://itdb.atrust.sangfor.com");
        assertThrows(ItDbWorkflowException.class, properties::validatedBaseUri);

        properties.setBaseUrl("https://example.com");
        properties.setAllowedHosts(List.of("itdb.atrust.sangfor.com"));
        assertThrows(ItDbWorkflowException.class, properties::validatedBaseUri);
    }
}
