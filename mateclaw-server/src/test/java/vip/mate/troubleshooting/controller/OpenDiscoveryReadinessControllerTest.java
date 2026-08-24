package vip.mate.troubleshooting.controller;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.agent.OpenDiscoveryReadinessService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OpenDiscoveryReadinessControllerTest {

    @Test
    void forwardsTheExactSystemAndServiceScopeToReadinessInspection() {
        OpenDiscoveryReadinessService readiness =
                mock(OpenDiscoveryReadinessService.class);
        OpenDiscoveryReadinessController controller =
                new OpenDiscoveryReadinessController(readiness);

        controller.readiness("CSDP", "csdp-task", 7L);

        verify(readiness).inspect(7L, "CSDP", "csdp-task");
    }
}
