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
    private static final java.util.Set<String> TIMED_INTAKE_PATHS = java.util.Set.of(
            "/api/v1/troubleshooting/incidents");
    /**
     * Scenario entries carry the key in the path, so they cannot be matched by
     * an exact set. The pattern stays deliberately tight — one segment between
     * the prefix and {@code /diagnoses} — because stamping an arrival time on a
     * request that is not an intake would put a fabricated {@code reportedAt}
     * into the north star.
     */
    private static final java.util.regex.Pattern TIMED_SCENARIO_PATH =
            java.util.regex.Pattern.compile(
                    "^/api/v1/troubleshooting/scenarios/[^/]+/diagnoses$");

    private final Clock clock;

    public TroubleshootingRequestTimingFilter() {
        this(Clock.systemUTC());
    }

    TroubleshootingRequestTimingFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !TIMED_INTAKE_PATHS.contains(path)
                && !TIMED_SCENARIO_PATH.matcher(path).matches();
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
