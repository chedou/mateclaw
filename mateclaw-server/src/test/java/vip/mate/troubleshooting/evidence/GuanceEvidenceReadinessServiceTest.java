package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GuanceEvidenceReadinessServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-29T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void reportsTheExactWorkspaceScopeWithoutReadingCredentialsWhenUnauthorized() {
        GuardedGuance config = new GuardedGuance();
        config.setEnabled(true);
        config.setBaseUrl("https://guance.example");
        config.setBindings(validBindings());
        config.setAssetBindings(List.of());
        EvidenceProperties properties = properties(config);
        GuanceEvidenceReadinessService service = new GuanceEvidenceReadinessService(
                properties,
                new GuanceEvidenceAdapter(
                        config, new ObjectMapper(), noCallTransport(), CLOCK));

        GuanceEvidenceReadiness result = service.inspect(7L, "CSDP", "session-svc");

        assertThat(result.status())
                .isEqualTo(GuanceEvidenceReadiness.Status.UNAUTHORIZED);
        assertThat(result.credentialState())
                .isEqualTo(GuanceEvidenceReadiness.CredentialState.NOT_INSPECTED);
        assertThat(result.uniqueAssetAuthorized()).isFalse();
        assertThat(result.blockers()).anyMatch(value -> value.contains("workspace asset"));
        assertThat(result.toString()).doesNotContain("secret", "query-template");
    }

    @Test
    void exposesCoreSignalReadinessWithoutDqlOrCredentialMaterial() {
        EvidenceProperties.Guance config = configuredGuance();
        EvidenceProperties properties = properties(config);
        GuanceEvidenceReadinessService service = new GuanceEvidenceReadinessService(
                properties,
                new GuanceEvidenceAdapter(
                        config, new ObjectMapper(), noCallTransport(), CLOCK));

        GuanceEvidenceReadiness result = service.inspect(7L, " csdp ", " SESSION-SVC ");

        assertThat(result.status())
                .isEqualTo(GuanceEvidenceReadiness.Status.READY_FOR_VALIDATION);
        assertThat(result.credentialState())
                .isEqualTo(GuanceEvidenceReadiness.CredentialState.CONFIGURED);
        assertThat(result.uniqueAssetAuthorized()).isTrue();
        assertThat(result.signals())
                .filteredOn(signal -> signal.signalKind().equals("log_search")
                        || signal.signalKind().equals("log_trace_bundle"))
                .allMatch(signal -> signal.status()
                        == GuanceEvidenceReadiness.SignalStatus.READY_FOR_VALIDATION);
        assertThat(result.signals())
                .filteredOn(signal -> signal.signalKind().equals("contrast_sample"))
                .singleElement()
                .satisfies(signal -> assertThat(signal.status())
                        .isEqualTo(GuanceEvidenceReadiness.SignalStatus.NOT_ROUTED));
        assertThat(result.signals())
                .filteredOn(signal -> signal.signalKind().equals("incident_impact"))
                .singleElement()
                .satisfies(signal -> assertThat(signal.status())
                        .isEqualTo(GuanceEvidenceReadiness.SignalStatus.UNAUTHORIZED));
        assertThat(result.toString())
                .contains("search-binding", "trace-binding")
                .doesNotContain("runtime-secret", "L::", "DF-API-KEY");
    }

    @Test
    void keepsAmbiguousAssetScopesFailClosed() {
        EvidenceProperties.Guance config = configuredGuance();
        config.setAssetBindings(List.of(
                assetBinding("CSDP", "session-svc"),
                assetBinding(" csdp ", " SESSION-SVC ")));
        EvidenceProperties properties = properties(config);
        GuanceEvidenceReadinessService service = new GuanceEvidenceReadinessService(
                properties,
                new GuanceEvidenceAdapter(
                        config, new ObjectMapper(), noCallTransport(), CLOCK));

        GuanceEvidenceReadiness result = service.inspect(7L, "CSDP", "session-svc");

        assertThat(result.status())
                .isEqualTo(GuanceEvidenceReadiness.Status.UNAUTHORIZED);
        assertThat(result.signals())
                .filteredOn(signal -> signal.signalKind().equals("log_search"))
                .singleElement()
                .satisfies(signal -> assertThat(signal.status())
                        .isEqualTo(GuanceEvidenceReadiness.SignalStatus.INVALID_BINDING));
    }

    @Test
    void keepsNormalizedRouteAmbiguityFailClosedBeforeReadingCredentials() {
        GuardedGuance config = new GuardedGuance();
        config.setEnabled(true);
        config.setBaseUrl("https://guance.example");
        config.setBindings(validBindings());
        config.setAssetBindings(List.of(assetBinding("CSDP", "session-svc")));
        EvidenceProperties properties = new EvidenceProperties();
        properties.setGuance(config);
        Map<String, Map<String, List<String>>> routes = new LinkedHashMap<>();
        routes.put("CSDP", coreRoutes());
        routes.put(" csdp ", coreRoutes());
        properties.setRoutes(routes);
        GuanceEvidenceReadinessService service = new GuanceEvidenceReadinessService(
                properties,
                new GuanceEvidenceAdapter(
                        config, new ObjectMapper(), noCallTransport(), CLOCK));

        GuanceEvidenceReadiness result = service.inspect(7L, "CSDP", "session-svc");

        assertThat(result.status())
                .isEqualTo(GuanceEvidenceReadiness.Status.UNAUTHORIZED);
        assertThat(result.credentialState())
                .isEqualTo(GuanceEvidenceReadiness.CredentialState.NOT_INSPECTED);
        assertThat(result.signals())
                .filteredOn(signal -> signal.signalKind().equals("log_search"))
                .singleElement()
                .satisfies(signal -> assertThat(signal.status())
                        .isEqualTo(GuanceEvidenceReadiness.SignalStatus.INVALID_BINDING));
    }

    @Test
    void neverExposesSecretShapedBindingReferences() {
        String secretShapedRef = "eyJabcdefgh.abcdefghijk.abcdefgh";
        EvidenceProperties.Guance config = configuredGuance();
        config.setBindings(Map.of(
                secretShapedRef, validBindings().get("search-binding"),
                "trace-binding", validBindings().get("trace-binding")));
        EvidenceProperties.AssetBinding asset = assetBinding("CSDP", "session-svc");
        asset.setSignalBindings(Map.of(
                "log_search", secretShapedRef,
                "log_trace_bundle", "trace-binding"));
        config.setAssetBindings(List.of(asset));
        EvidenceProperties properties = properties(config);
        GuanceEvidenceReadinessService service = new GuanceEvidenceReadinessService(
                properties,
                new GuanceEvidenceAdapter(
                        config, new ObjectMapper(), noCallTransport(), CLOCK));

        GuanceEvidenceReadiness result = service.inspect(7L, "CSDP", "session-svc");

        assertThat(result.status())
                .isEqualTo(GuanceEvidenceReadiness.Status.UNAUTHORIZED);
        assertThat(result.credentialState())
                .isEqualTo(GuanceEvidenceReadiness.CredentialState.NOT_INSPECTED);
        assertThat(result.toString()).doesNotContain(secretShapedRef);
    }

    private EvidenceProperties properties(EvidenceProperties.Guance config) {
        EvidenceProperties properties = new EvidenceProperties();
        properties.setGuance(config);
        properties.setRoutes(Map.of(
                "CSDP", coreRoutes()));
        return properties;
    }

    private Map<String, List<String>> coreRoutes() {
        return Map.of(
                "log_search", List.of("guance", "recorded-replay"),
                "log_trace_bundle", List.of("guance", "recorded-replay"),
                "contrast_sample", List.of("recorded-replay"),
                "incident_impact", List.of("guance", "recorded-replay"));
    }

    private EvidenceProperties.Guance configuredGuance() {
        EvidenceProperties.Guance config = new EvidenceProperties.Guance();
        config.setEnabled(true);
        config.setBaseUrl("https://guance.example");
        config.setApiKey("runtime-secret");
        config.setBindings(validBindings());
        config.setAssetBindings(List.of(assetBinding("CSDP", "session-svc")));
        return config;
    }

    private Map<String, EvidenceProperties.Binding> validBindings() {
        return Map.of(
                "search-binding", binding(
                        "L", "L::logs:(count,ps_id,message) {service='{{service}}'} [{{window}}]",
                        Map.of("count", "match_count", "message", "sample_message"), 1),
                "trace-binding", binding(
                        "L", "L::logs:(ps_id,time,service,status,message) {ps_id='{{ps_id}}'} [{{window}}]",
                        Map.of("time", "timestamp", "status", "level"), 200));
    }

    private EvidenceProperties.Binding binding(
            String namespace,
            String query,
            Map<String, String> aliases,
            int maxRows) {
        EvidenceProperties.Binding binding = new EvidenceProperties.Binding();
        binding.setNamespace(namespace);
        binding.setSummary("configured binding");
        binding.setQueryTemplate(query);
        binding.setFieldAliases(aliases);
        binding.setMaxRows(maxRows);
        return binding;
    }

    private EvidenceProperties.AssetBinding assetBinding(String system, String service) {
        EvidenceProperties.AssetBinding binding = new EvidenceProperties.AssetBinding();
        binding.setWorkspaceId(7L);
        binding.setSystem(system);
        binding.setService(service);
        binding.setSignalBindings(Map.of(
                "log_search", "search-binding",
                "log_trace_bundle", "trace-binding"));
        return binding;
    }

    /** Readiness inspection must not touch the network at all — either verb. */
    private EvidenceHttpTransport noCallTransport() {
        return new EvidenceHttpTransport() {
            @Override
            public Response postJson(java.net.URI uri, java.util.Map<String, String> headers,
                    String body, java.time.Duration timeout) {
                throw new AssertionError("readiness inspection must not query Guance");
            }

            @Override
            public Response get(java.net.URI uri, java.util.Map<String, String> headers,
                    java.time.Duration timeout) {
                throw new AssertionError("readiness inspection must not query any source");
            }
        };
    }

    private static final class GuardedGuance extends EvidenceProperties.Guance {
        @Override
        public String getApiKey() {
            throw new AssertionError("credential must not be read before exact authorization");
        }
    }
}
