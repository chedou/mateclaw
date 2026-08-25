package vip.mate.troubleshooting.service;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.IncidentImpact;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentServiceIdentityResolverTest {

    private static final Instant OCCURRED_AT =
            Instant.parse("2026-08-25T03:42:12Z");

    @Test
    void resolvesAPlaceholderAppFromTheRepeatedBusinessStackOwner() {
        IncidentContext reported = incident(
                "main",
                """
                github.com/csp/csp-service/v3/pkg/alertor.Log
                github.com/csp/csp-service/v3/pkg/css/util.(CssClient).SendGeneralRequestWithHeaders
                github.com/csp/csp-service/v3/pkg/css/partner.(partnerClient).GetPartnerUserInfo
                github.com/csp/csp-service/v3/pkg/css/staff.(*partnerClient).GetStaffs
                """);

        IncidentContext resolved = IncidentServiceIdentityResolver.resolve(reported);

        assertThat(resolved.service()).isEqualTo("csp-api");
        assertThat(resolved.rawInput()).isEqualTo(reported.rawInput());
    }

    @Test
    void keepsAnAlreadySpecificReportedService() {
        IncidentContext reported = incident(
                "csdp-wechat",
                "github.com/csp/csp-service/v3/pkg/css/staff.(*partnerClient).GetStaffs\n"
                        + "github.com/csp/csp-service/v3/pkg/css/staff.(*partnerClient).GetStaffs");

        assertThat(IncidentServiceIdentityResolver.resolve(reported)).isSameAs(reported);
    }

    @Test
    void rejectsAPlaceholderWhenTheStackHasCompetingOwners() {
        IncidentContext reported = incident(
                "main",
                """
                github.com/csp/csp-service/v3/pkg/css/staff.(*partnerClient).GetStaffs
                github.com/csp/csp-service/v3/pkg/css/staff.(*partnerClient).GetStaffs
                github.com/csp/other-service/v2/pkg/client.Call
                github.com/csp/other-service/v2/pkg/client.Retry
                """);

        assertThatThrownBy(() -> IncidentServiceIdentityResolver.resolve(reported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法唯一确认真实服务");
    }

    @Test
    void rejectsAPlaceholderWhenOnlyOneUncorroboratedFrameNamesARepository() {
        IncidentContext reported = incident(
                "main",
                "github.com/csp/csp-service/v3/pkg/alertor.Log");

        assertThatThrownBy(() -> IncidentServiceIdentityResolver.resolve(reported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请补充真实服务名");
    }

    @Test
    void ignoresUrlLinesWhenResolvingTheCallerFromStackFrames() {
        IncidentContext reported = incident(
                "main",
                """
                https://gateway.example.com/org/downstream/v3/pkg/client/request
                https://gateway.example.com/org/downstream/v3/pkg/client/retry
                github.com/csp/csp-service/v3/pkg/css/partner.(partnerClient).GetPartnerUserInfo
                github.com/csp/csp-service/v3/pkg/css/staff.(*partnerClient).GetStaffs
                """);

        assertThat(IncidentServiceIdentityResolver.resolve(reported).service())
                .isEqualTo("csp-api");
    }

    @Test
    void rejectsACorroboratedRepositoryWithoutAReviewedObservabilityMapping() {
        IncidentContext reported = incident(
                "main",
                """
                github.com/csp/unmapped-service/v2/pkg/client.Call
                github.com/csp/unmapped-service/v2/pkg/client.Retry
                """);

        assertThatThrownBy(() -> IncidentServiceIdentityResolver.resolve(reported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("观测服务映射");
    }

    private IncidentContext incident(String service, String rawInput) {
        return new IncidentContext(
                "inc-main-partner-user-info-20260825-114212",
                "CSDP",
                service,
                "HTTP_502",
                "调用接口异常",
                "P1",
                IncidentImpact.unknown("影响待确认"),
                null,
                OCCURRED_AT,
                null,
                "alert-webhook",
                IncidentCompleteness.STRUCTURED,
                rawInput);
    }
}
