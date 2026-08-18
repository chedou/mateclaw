package vip.mate.troubleshooting.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioDiagnosisRequestTest {

    private static final Instant REPORTED_AT = Instant.parse("2026-08-04T01:00:00Z");

    @Test
    @DisplayName("场景未填写故障时间时使用服务端收到请求的时间")
    void fallsBackToReportedAtWhenOccurredAtIsAbsent() {
        ScenarioDiagnosisRequest request = request(null);

        assertThat(request.toIncidentContext(REPORTED_AT).occurredAt())
                .isEqualTo(REPORTED_AT);
    }

    @Test
    @DisplayName("场景填写故障时间时保留该时间供真实证据窗口使用")
    void keepsTheSelectedFailureTime() {
        Instant occurredAt = Instant.parse("2026-07-31T08:30:00Z");

        assertThat(request(occurredAt).toIncidentContext(REPORTED_AT).occurredAt())
                .isEqualTo(occurredAt);
    }

    @Test
    @DisplayName("场景未显式选择正式模式时安全默认为演练")
    void omittedRehearsalDefaultsToSafeRehearsalMode() {
        ScenarioDiagnosisRequest request = new ScenarioDiagnosisRequest(
                "CSDP",
                "csdp-task",
                "CTI 创建会话失败",
                "P1",
                null,
                null,
                null,
                null);

        assertThat(request.isRehearsal()).isTrue();
    }

    private ScenarioDiagnosisRequest request(Instant occurredAt) {
        return new ScenarioDiagnosisRequest(
                "CSDP",
                "csdp-session-service",
                "会话消息发送失败",
                "P2",
                null,
                null,
                occurredAt,
                true);
    }
}
