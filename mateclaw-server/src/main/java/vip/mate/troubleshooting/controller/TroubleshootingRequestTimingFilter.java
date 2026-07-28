package vip.mate.troubleshooting.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;

/** Captures incident arrival before Spring maps or validates the request body. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class TroubleshootingRequestTimingFilter extends OncePerRequestFilter {

    static final String REPORTED_AT_ATTRIBUTE = "vip.mate.troubleshooting.reportedAt";
    private static final String INCIDENT_REPORT_PATH = "/api/v1/troubleshooting/incidents";

    private final Clock clock;

    public TroubleshootingRequestTimingFilter() {
        this(Clock.systemUTC());
    }

    TroubleshootingRequestTimingFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String expectedUri = request.getContextPath() + INCIDENT_REPORT_PATH;
        return !"POST".equals(request.getMethod())
                || !expectedUri.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        request.setAttribute(REPORTED_AT_ATTRIBUTE, Instant.now(clock));
        filterChain.doFilter(request, response);
    }
}
