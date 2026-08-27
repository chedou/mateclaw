package vip.mate.tool.itdb;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItDbWorkflowPropertiesTest {

    @Test
    void defaultEndpointUsesDocumentedInternalHostButRequiresExplicitHttpOptIn() {
        ItDbWorkflowProperties properties = new ItDbWorkflowProperties();

        assertThrows(ItDbWorkflowException.class, properties::validatedBaseUri);

        properties.setAllowInsecureHttp(true);
        assertEquals("http://itdb.sangfor.com", properties.validatedBaseUri().toString());
    }

    @Test
    void rejectsHttpAndHostsOutsideDeploymentAllowlist() {
        ItDbWorkflowProperties properties = new ItDbWorkflowProperties();
        properties.setBaseUrl("http://itdb.sangfor.com");
        assertThrows(ItDbWorkflowException.class, properties::validatedBaseUri);

        properties.setBaseUrl("https://example.com");
        properties.setAllowedHosts(List.of("itdb.sangfor.com"));
        assertThrows(ItDbWorkflowException.class, properties::validatedBaseUri);
    }

    @Test
    void allowsExplicitInternalHttpEndpointWithoutAllowingOtherHosts() {
        ItDbWorkflowProperties properties = new ItDbWorkflowProperties();
        properties.setBaseUrl("http://itdb.sangfor.com");
        properties.setAllowedHosts(List.of("itdb.sangfor.com"));

        assertThrows(ItDbWorkflowException.class, properties::validatedBaseUri);

        properties.setAllowInsecureHttp(true);
        assertEquals("http://itdb.sangfor.com", properties.validatedBaseUri().toString());

        properties.setBaseUrl("http://example.com");
        assertThrows(ItDbWorkflowException.class, properties::validatedBaseUri);
    }
}
