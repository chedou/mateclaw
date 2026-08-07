package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Manually selected live contract check; excluded from the default Surefire naming pattern. */
class GuanceEvidenceLiveContractIT {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(EvidenceAutoConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("spring.profiles.active=csdp-guance-evidence-pilot");

    @Test
    @EnabledIfEnvironmentVariable(
            named = "MATECLAW_TROUBLESHOOTING_GUANCE_API_KEY",
            matches = ".+")
    void observesTheSameContractThroughTheExactPilotProfileAndRouter() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EvidenceHttpTransport.class))
                    .isInstanceOf(NativeCurlEvidenceHttpTransport.class);

            EvidenceRequest request = new EvidenceRequest(
                    "EV-LIVE-PROFILE-SEARCH",
                    "log_search",
                    "verify live profile contract",
                    Map.of("search_term", "message_send_failed"),
                    "-24h",
                    true);
            IncidentContext incident = new IncidentContext(
                    "inc-live-profile",
                    "CSDP",
                    "csdp-session-service",
                    null,
                    "live profile contract verification",
                    "P2",
                    "read-only",
                    null,
                    Instant.now(),
                    null,
                    "manual",
                    IncidentCompleteness.LOG,
                    null);

            EvidenceSourceRouter router = context.getBean(EvidenceSourceRouter.class);
            var result = router
                    .collect(1L, request, incident, Set.of("guance"));

            assertThat(result.status() == EvidenceStatus.NORMAL).isTrue();
            assertThat(result.observed().keySet())
                    .containsExactlyInAnyOrder("match_count", "ps_id", "sample_message");
            assertThat(nonBlank(result.observed().get("ps_id"))).isTrue();
            assertThat(nonBlank(result.observed().get("sample_message"))).isTrue();

            String psId = String.valueOf(result.observed().get("ps_id"));
            EvidenceRequest traceRequest = new EvidenceRequest(
                    "EV-LIVE-PROFILE-TRACE",
                    "log_trace_bundle",
                    "verify live profile trace contract",
                    Map.of("ps_id", psId),
                    "-24h",
                    true);
            var trace = router.collect(1L, traceRequest, incident, Set.of("guance"));

            assertThat(trace.status() == EvidenceStatus.NORMAL).isTrue();
            assertThat(trace.observed().keySet())
                    .containsExactlyInAnyOrder("ps_id", "entries");
            assertThat(Objects.equals(trace.observed().get("ps_id"), psId)).isTrue();
            assertThat(trace.observed().get("entries") instanceof java.util.List<?> entries
                    && !entries.isEmpty()).isTrue();

            EvidenceRequest contrastRequest = new EvidenceRequest(
                    "EV-LIVE-PROFILE-CONTRAST",
                    "contrast_sample",
                    "verify live profile contrast contract",
                    Map.of(),
                    "-24h",
                    true);
            var contrast = router.collect(
                    1L, contrastRequest, incident, Set.of("guance"));

            assertThat(contrast.status() == EvidenceStatus.NORMAL)
                    .as(contrast.summary())
                    .isTrue();
            assertThat(contrast.observed().keySet()).containsExactlyInAnyOrder(
                    "discriminating_feature",
                    "failure_sample_count",
                    "failure_match_count",
                    "success_sample_count",
                    "success_match_count");
            assertThat(Objects.equals(
                    contrast.observed().get("discriminating_feature"),
                    "message_length_eq_2875")).isTrue();
            long failureSamples = number(contrast.observed().get("failure_sample_count"));
            long failureMatches = number(contrast.observed().get("failure_match_count"));
            long successSamples = number(contrast.observed().get("success_sample_count"));
            long successMatches = number(contrast.observed().get("success_match_count"));
            assertThat(failureSamples).isPositive();
            assertThat(failureMatches).isBetween(0L, failureSamples);
            assertThat(successSamples).isPositive();
            assertThat(successMatches).isBetween(0L, successSamples);
            assertThat(java.math.BigInteger.valueOf(failureMatches)
                    .multiply(java.math.BigInteger.valueOf(successSamples)))
                    .isGreaterThan(java.math.BigInteger.valueOf(successMatches)
                            .multiply(java.math.BigInteger.valueOf(failureSamples)));
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(
            named = "MATECLAW_TROUBLESHOOTING_GUANCE_API_KEY",
            matches = ".+")
    void observesTheSanitizedApplicationErrorScanContract() {
        contextRunner.run(context -> {
            EvidenceRequest request = new EvidenceRequest(
                    "EV-LIVE-ERROR-SCAN",
                    "error_log_scan",
                    "verify aggregate application error scan",
                    Map.of(),
                    "-15m",
                    true);
            IncidentContext incident = new IncidentContext(
                    "inc-live-error-scan",
                    "CSDP",
                    "csdp-session-service",
                    null,
                    "application error scan contract verification",
                    "P2",
                    "read-only",
                    null,
                    Instant.now(),
                    null,
                    "manual",
                    IncidentCompleteness.LOG,
                    null);

            var result = context.getBean(EvidenceSourceRouter.class)
                    .collect(1L, request, incident, Set.of("guance"));

            assertThat(result.status())
                    .as(result.summary())
                    .isEqualTo(EvidenceStatus.NORMAL);
            assertThat(result.query()).isEmpty();
            assertThat(result.observed())
                    .containsKey("error_count")
                    .doesNotContainKeys("message", "content", "host");
            assertThat(number(result.observed().get("error_count"))).isNotNegative();
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(
            named = "MATECLAW_TROUBLESHOOTING_GUANCE_API_KEY",
            matches = ".+")
    void observesTheCtiCreateConversationThreeStageContract() {
        contextRunner.run(context -> {
            IncidentContext incident = new IncidentContext(
                    "inc-live-cti-create-conversation",
                    "CSDP",
                    "csdp-task",
                    null,
                    "CTI创建会话失败",
                    "P1",
                    "read-only",
                    null,
                    Instant.parse("2026-08-07T09:24:00Z"),
                    null,
                    "manual",
                    IncidentCompleteness.LOG,
                    null);
            EvidenceSourceRouter router = context.getBean(EvidenceSourceRouter.class);

            var search = router.collect(
                    1L,
                    new EvidenceRequest(
                            "EV-LIVE-CTI-SEARCH",
                            "log_search",
                            "verify CTI failure wrapper contract",
                            Map.of("search_term", "cti_create_conversation_failed"),
                            "-15m",
                            true),
                    incident,
                    Set.of("guance"));

            assertThat(search.status()).as(search.summary()).isEqualTo(EvidenceStatus.NORMAL);
            assertThat(number(search.observed().get("match_count"))).isPositive();
            assertThat(nonBlank(search.observed().get("ps_id"))).isTrue();
            assertThat(search.observed().get("sample_message"))
                    .isEqualTo("cti_create_conversation_failed");

            String correlationId = String.valueOf(search.observed().get("ps_id"));
            var trace = router.collect(
                    1L,
                    new EvidenceRequest(
                            "EV-LIVE-CTI-TRACE",
                            "log_trace_bundle",
                            "verify CTI correlation trace contract",
                            Map.of("ps_id", correlationId),
                            "-15m",
                            true),
                    incident,
                    Set.of("guance"));
            assertThat(trace.status()).as(trace.summary()).isEqualTo(EvidenceStatus.NORMAL);
            assertThat(trace.observed().get("ps_id")).isEqualTo(correlationId);
            assertThat(trace.observed().get("entries"))
                    .isInstanceOfSatisfying(java.util.List.class,
                            entries -> assertThat(entries).isNotEmpty());
            assertThat(traceContains(trace.observed().get("entries"), "701018")).isTrue();
            assertThat(traceContains(trace.observed().get("entries"), "701022")).isTrue();
            assertThat(traceContains(trace.observed().get("entries"), "CreateConversation"))
                    .isTrue();

            var contrast = router.collect(
                    1L,
                    new EvidenceRequest(
                            "EV-LIVE-CTI-CONTRAST",
                            "contrast_sample",
                            "verify CTI failure and success cohorts",
                            Map.of(
                                    "scenario_key", "cti_create_conversation_failed",
                                    "exclude_ps_id", correlationId),
                            "-15m",
                            true),
                    incident,
                    Set.of("guance"));
            assertThat(contrast.status()).as(contrast.summary())
                    .isEqualTo(EvidenceStatus.NORMAL);
            assertThat(contrast.observed().get("discriminating_feature"))
                    .isEqualTo("inner_701022_on_failed_trace");
            assertThat(number(contrast.observed().get("failure_sample_count"))).isEqualTo(1L);
            assertThat(number(contrast.observed().get("failure_match_count"))).isEqualTo(1L);
            assertThat(number(contrast.observed().get("success_sample_count"))).isEqualTo(4L);
            assertThat(number(contrast.observed().get("success_match_count"))).isZero();
        });
    }

    private static boolean nonBlank(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : -1L;
    }

    private static boolean traceContains(Object rawEntries, String token) {
        if (!(rawEntries instanceof java.util.List<?> entries)) {
            return false;
        }
        return entries.stream().anyMatch(entry -> entry instanceof Map<?, ?> fields
                && fields.get("message") instanceof String message
                && message.contains(token));
    }
}
