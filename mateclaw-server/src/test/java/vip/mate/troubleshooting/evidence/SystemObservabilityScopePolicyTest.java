package vip.mate.troubleshooting.evidence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SystemObservabilityScopePolicyTest {

    @Test
    void onlyAllowsTheV1GenericSignalAndRequiresRuntimeServiceFiltering() {
        EvidenceProperties.Binding safe = new EvidenceProperties.Binding();
        safe.setQueryTemplate(
                "L::logs:(count(*) as error_count) {service='{{service}}'}");
        EvidenceProperties.Binding unsafe = new EvidenceProperties.Binding();
        unsafe.setQueryTemplates(List.of(
                "L::logs:(count(*) as error_count) {level='error'}"));

        assertThat(SystemObservabilityScopePolicy.allowsSignal("ERROR_LOG_SCAN"))
                .isTrue();
        assertThat(SystemObservabilityScopePolicy.allowsSignal("log_search"))
                .isFalse();
        assertThat(SystemObservabilityScopePolicy.safelyFiltersRuntimeService(safe))
                .isTrue();
        assertThat(SystemObservabilityScopePolicy.safelyFiltersRuntimeService(unsafe))
                .isFalse();
    }
}
