package vip.mate.troubleshooting.evidence;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GuanceBindingFingerprintServiceTest {

    @Test
    void fingerprintsTheExactRoutesQueriesAndFieldAliasesWithoutCredentialMaterial() {
        EvidenceProperties properties = properties();
        GuanceBindingFingerprintService service =
                new GuanceBindingFingerprintService(properties);

        GuanceBindingFingerprintService.Snapshot first =
                service.current(7L, "CSDP", "session-svc").orElseThrow();
        properties.getGuance().setApiKey("rotated-runtime-secret");
        GuanceBindingFingerprintService.Snapshot afterCredentialRotation =
                service.current(7L, "csdp", "SESSION-SVC").orElseThrow();
        properties.getGuance().getBindings().get("search-binding")
                .getQueryOptions().setInterval(11);
        GuanceBindingFingerprintService.Snapshot afterQueryOptionsChange =
                service.current(7L, "CSDP", "session-svc").orElseThrow();
        properties.getGuance().getBindings().get("search-binding")
                .setQueryTemplate("L::other_measurement:(message=@search)");
        GuanceBindingFingerprintService.Snapshot afterQueryChange =
                service.current(7L, "CSDP", "session-svc").orElseThrow();
        properties.getGuance().getBindings().get("contrast-binding")
                .setConstantFields(Map.of(
                        "discriminating_feature", "message_length_eq_3000"));
        GuanceBindingFingerprintService.Snapshot afterConstantChange =
                service.current(7L, "CSDP", "session-svc").orElseThrow();

        assertThat(first.scopeKey()).matches("[a-f0-9]{64}");
        assertThat(first.bindingFingerprint()).matches("[a-f0-9]{64}");
        assertThat(afterCredentialRotation.scopeKey()).isEqualTo(first.scopeKey());
        assertThat(afterCredentialRotation.bindingFingerprint())
                .isEqualTo(first.bindingFingerprint());
        assertThat(afterQueryOptionsChange.bindingFingerprint())
                .isNotEqualTo(first.bindingFingerprint());
        assertThat(afterQueryChange.bindingFingerprint())
                .isNotEqualTo(afterQueryOptionsChange.bindingFingerprint());
        assertThat(afterConstantChange.bindingFingerprint())
                .isNotEqualTo(afterQueryChange.bindingFingerprint());
        assertThat(first.toString())
                .doesNotContain("runtime-secret", "L::logs", "message=@search");
    }

    @Test
    void rejectsAmbiguousNormalizedAssetsAndMissingCoreRoutes() {
        EvidenceProperties properties = properties();
        EvidenceProperties.AssetBinding duplicate = asset("csdp", "SESSION-SVC");
        properties.getGuance().setAssetBindings(List.of(
                asset("CSDP", "session-svc"),
                duplicate));

        GuanceBindingFingerprintService service =
                new GuanceBindingFingerprintService(properties);

        assertThat(service.current(7L, "CSDP", "session-svc")).isEmpty();

        properties.getGuance().setAssetBindings(
                List.of(asset("CSDP", "session-svc")));
        properties.getRoutes().get("CSDP").remove("log_trace_bundle");

        assertThat(service.current(7L, "CSDP", "session-svc")).isEmpty();
    }

    private EvidenceProperties properties() {
        EvidenceProperties properties = new EvidenceProperties();
        properties.setRoutes(new LinkedHashMap<>(Map.of(
                "CSDP",
                new LinkedHashMap<>(Map.of(
                        "log_search", List.of("guance"),
                        "log_trace_bundle", List.of("guance"),
                        "contrast_sample", List.of("guance"))))));
        properties.getGuance().setEnabled(true);
        properties.getGuance().setBaseUrl("https://guance.example.test");
        properties.getGuance().setApiKey("runtime-secret");
        properties.getGuance().setQueryPath("/api/v1/query");
        properties.getGuance().setTimeout(Duration.ofSeconds(5));
        properties.getGuance().setAssetBindings(
                List.of(asset("CSDP", "session-svc")));

        EvidenceProperties.Binding search = new EvidenceProperties.Binding();
        search.setNamespace("logs");
        search.setQueryTemplate("L::logs:(message=@search)");
        search.setMaxRows(200);
        search.setQueryOptions(new EvidenceProperties.QueryOptions());
        search.setFieldAliases(new LinkedHashMap<>(Map.of(
                "message", "message",
                "psid", "ps_id")));
        EvidenceProperties.Binding trace = new EvidenceProperties.Binding();
        trace.setNamespace("logs");
        trace.setQueryTemplate("L::logs:(psid=@ps_id)");
        trace.setMaxRows(200);
        trace.setFieldAliases(new LinkedHashMap<>(Map.of(
                "service", "service",
                "status", "status")));
        EvidenceProperties.Binding contrast = new EvidenceProperties.Binding();
        contrast.setNamespace("logs");
        contrast.setQueryTemplates(List.of(
                "L::logs:(count(*) as failure_sample_count)",
                "L::logs:(count(*) as success_sample_count)"));
        contrast.setConstantFields(Map.of(
                "discriminating_feature", "message_length_eq_2875"));
        properties.getGuance().setBindings(new LinkedHashMap<>(Map.of(
                "search-binding", search,
                "trace-binding", trace,
                "contrast-binding", contrast)));
        return properties;
    }

    private EvidenceProperties.AssetBinding asset(String system, String service) {
        EvidenceProperties.AssetBinding asset =
                new EvidenceProperties.AssetBinding();
        asset.setWorkspaceId(7L);
        asset.setSystem(system);
        asset.setService(service);
        asset.setSignalBindings(new LinkedHashMap<>(Map.of(
                "log_search", "search-binding",
                "log_trace_bundle", "trace-binding",
                "contrast_sample", "contrast-binding")));
        return asset;
    }
}
