package vip.mate.troubleshooting.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentReportRequestTest {

    @Test
    void omittedRehearsalDefaultsToSafeRehearsalMode() {
        IncidentReportRequest request = new IncidentReportRequest(
                null,
                "CSDP",
                "csdp-task",
                null,
                "CTI 创建会话失败",
                "P1",
                null,
                null,
                null,
                null,
                "web:formal-workbench",
                null,
                null,
                null,
                null);

        assertThat(request.isRehearsal()).isTrue();
    }

    @Test
    void onlyExplicitFalseRequestsFormalMode() {
        IncidentReportRequest request = new IncidentReportRequest(
                null,
                "CSDP",
                "csdp-task",
                null,
                "CTI 创建会话失败",
                "P1",
                null,
                null,
                null,
                null,
                "web:formal-workbench",
                null,
                null,
                null,
                false);

        assertThat(request.isRehearsal()).isFalse();
    }
}
