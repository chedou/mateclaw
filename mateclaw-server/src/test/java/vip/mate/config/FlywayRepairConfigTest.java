package vip.mate.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class FlywayRepairConfigTest {

    @Test
    void productionDefaultMigratesWithoutRepairingFailureEvidence() throws Exception {
        Flyway flyway = mock(Flyway.class);
        FlywayMigrationInitializer initializer =
                new FlywayRepairConfig().flywayInitializer(flyway, false);

        initializer.afterPropertiesSet();

        verify(flyway, never()).repair();
        verify(flyway).migrate();
    }

    @Test
    void explicitDesktopOptInRepairsBeforeMigrating() throws Exception {
        Flyway flyway = mock(Flyway.class);
        FlywayMigrationInitializer initializer =
                new FlywayRepairConfig().flywayInitializer(flyway, true);

        initializer.afterPropertiesSet();

        InOrder order = inOrder(flyway);
        order.verify(flyway).repair();
        order.verify(flyway).migrate();
    }
}
