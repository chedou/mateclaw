package vip.mate.troubleshooting.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TroubleshootingRequestTimingFilterTest {

    @Test
    void capturesReportArrivalBeforeTheDownstreamChainRuns() throws Exception {
        Instant arrivedAt = Instant.parse("2026-07-29T01:02:03Z");
        TroubleshootingRequestTimingFilter filter = new TroubleshootingRequestTimingFilter(
                Clock.fixed(arrivedAt, ZoneOffset.UTC));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/troubleshooting/incidents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Object> observed = new AtomicReference<>();

        filter.doFilter(request, response, (downstreamRequest, downstreamResponse) ->
                observed.set(downstreamRequest.getAttribute(
                        TroubleshootingRequestTimingFilter.REPORTED_AT_ATTRIBUTE)));

        assertThat(observed.get()).isEqualTo(arrivedAt);
    }

    @Test
    void ignoresOtherRequests() throws Exception {
        TroubleshootingRequestTimingFilter filter = new TroubleshootingRequestTimingFilter(
                Clock.fixed(Instant.parse("2026-07-29T01:02:03Z"), ZoneOffset.UTC));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/troubleshooting/diagnoses");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (downstreamRequest, downstreamResponse) -> { });

        assertThat(request.getAttribute(
                TroubleshootingRequestTimingFilter.REPORTED_AT_ATTRIBUTE)).isNull();
    }

    @Test
    void capturesDeploymentTopologyScenarioArrival() throws Exception {
        Instant arrivedAt = Instant.parse("2026-07-31T01:02:03Z");
        TroubleshootingRequestTimingFilter filter = new TroubleshootingRequestTimingFilter(
                Clock.fixed(arrivedAt, ZoneOffset.UTC));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/troubleshooting/scenarios/deployment-topology/diagnoses");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (downstreamRequest, downstreamResponse) -> { });

        assertThat(request.getAttribute(
                TroubleshootingRequestTimingFilter.REPORTED_AT_ATTRIBUTE))
                .isEqualTo(arrivedAt);
    }
}
