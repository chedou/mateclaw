package vip.mate.troubleshooting.intake;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import vip.mate.MateClawApplication;
import vip.mate.troubleshooting.repository.TroubleshootingIntakeMessageReceiptMapper;
import vip.mate.troubleshooting.repository.TroubleshootingIntakeInvestigationMapper;
import vip.mate.troubleshooting.repository.TroubleshootingIntakeSessionMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TroubleshootingIntakeSessionWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WiringConfiguration.class)
            .withBean(
                    TroubleshootingIntakeSessionMapper.class,
                    () -> mock(TroubleshootingIntakeSessionMapper.class))
            .withBean(
                    TroubleshootingIntakeMessageReceiptMapper.class,
                    () -> mock(TroubleshootingIntakeMessageReceiptMapper.class))
            .withBean(
                    TroubleshootingIntakeInvestigationMapper.class,
                    () -> mock(TroubleshootingIntakeInvestigationMapper.class))
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
            .withBean(
                    PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class));

    @Test
    void productionConstructorCanBeAutowiredWhenTestSeamAlsoExists() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(TroubleshootingIntakeSessionService.class));
    }

    @Test
    void intakeMappersStayInsideTheApplicationMapperScanContract() {
        MapperScan mapperScan = MateClawApplication.class.getAnnotation(MapperScan.class);

        assertThat(mapperScan.value()).contains("vip.mate.**.repository");
        assertThat(TroubleshootingIntakeSessionMapper.class.getPackageName())
                .endsWith(".repository");
        assertThat(TroubleshootingIntakeMessageReceiptMapper.class.getPackageName())
                .endsWith(".repository");
        assertThat(TroubleshootingIntakeInvestigationMapper.class.getPackageName())
                .endsWith(".repository");
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TroubleshootingIntakeSessionService.class)
    static class WiringConfiguration {
    }
}
