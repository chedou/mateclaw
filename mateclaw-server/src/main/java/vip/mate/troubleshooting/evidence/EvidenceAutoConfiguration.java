package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** Composes evidence ports and adapters without enabling either source by default. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EvidenceProperties.class)
public class EvidenceAutoConfiguration {

    @Bean
    EvidenceHttpTransport evidenceHttpTransport(EvidenceProperties properties) {
        EvidenceProperties.Guance guance = properties.getGuance();
        String transport = guance.getTransport() == null
                ? ""
                : guance.getTransport().trim().toLowerCase(Locale.ROOT);
        if ("native-curl".equals(transport)) {
            return new NativeCurlEvidenceHttpTransport(guance.getNativeCurlExecutable());
        }
        if (!"jdk".equals(transport)) {
            throw new IllegalStateException("Unsupported Guance evidence transport");
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new JdkEvidenceHttpTransport(client);
    }

    @Bean
    GuanceEvidenceAdapter guanceEvidenceAdapter(
            EvidenceProperties properties,
            ObjectMapper objectMapper,
            EvidenceHttpTransport transport,
            ObjectProvider<WorkspaceObservabilityAssets> workspaceAssets,
            ObjectProvider<WorkspaceEvidenceContracts> workspaceContracts) {
        return new GuanceEvidenceAdapter(
                properties.getGuance(), objectMapper, transport,
                workspaceAssets.getIfAvailable(() -> WorkspaceObservabilityAssets.NONE),
                workspaceContracts.getIfAvailable(() -> WorkspaceEvidenceContracts.NONE),
                Clock.systemUTC());
    }

    /**
     * 默认关闭。关闭时 binding 为 null，适配器 health 报 DISABLED，
     * 且 {@code supports} 一律 false——它不会被路由选中，也不会假装能取证。
     */
    @Bean
    PrometheusEvidenceAdapter prometheusEvidenceAdapter(
            EvidenceProperties properties,
            ObjectMapper objectMapper,
            EvidenceHttpTransport transport) {
        EvidenceProperties.Prometheus config = properties.getPrometheus();
        PrometheusEvidenceAdapter.Binding binding = null;
        if (config.isEnabled() && config.getBaseUrl() != null
                && !config.getBaseUrl().isBlank()) {
            binding = new PrometheusEvidenceAdapter.Binding(
                    java.net.URI.create(config.getBaseUrl().trim()),
                    config.getFieldQueries(),
                    config.getBearerToken());
        }
        return new PrometheusEvidenceAdapter(
                binding, transport, objectMapper, Clock.systemUTC());
    }

    /** 默认关闭；串联字段没配时 binding 不可用，health 诚实报 DEGRADED。 */
    @Bean
    ElasticsearchEvidenceAdapter elasticsearchEvidenceAdapter(
            EvidenceProperties properties,
            ObjectMapper objectMapper,
            EvidenceHttpTransport transport) {
        EvidenceProperties.Elasticsearch config = properties.getElasticsearch();
        ElasticsearchEvidenceAdapter.Binding binding = null;
        if (config.isEnabled() && config.getBaseUrl() != null
                && !config.getBaseUrl().isBlank()) {
            binding = new ElasticsearchEvidenceAdapter.Binding(
                    java.net.URI.create(config.getBaseUrl().trim()),
                    config.getIndex(),
                    config.getCorrelationField(),
                    config.getMessageField(),
                    config.getBearerToken());
        }
        return new ElasticsearchEvidenceAdapter(
                binding, transport, objectMapper, Clock.systemUTC());
    }

    @Bean
    RecordedReplayAdapter recordedReplayAdapter(
            EvidenceProperties properties,
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource(
                properties.getRecordedReplay().getResource());
        return new RecordedReplayAdapter(
                properties.getRecordedReplay(), objectMapper, resource, Clock.systemUTC());
    }

    /**
     * Workspace 声明的路由优先，部署级 YAML 作为回落。
     *
     * <p>用 {@code ObjectProvider} 而不是直接注入：路由服务要读适配器，而适配器和
     * router 在同一份配置里组装，直接注入会绕成 Spring 循环依赖。取不到时退回
     * {@link WorkspaceEvidenceRoutes#NONE}——行为与本特性引入之前完全一致。</p>
     */
    @Bean
    EvidenceSourceRouter evidenceSourceRouter(
            List<EvidenceSourceAdapter> adapters,
            EvidenceProperties properties,
            ObjectProvider<WorkspaceEvidenceRoutes> workspaceRoutes) {
        return new EvidenceSourceRouter(
                adapters,
                properties,
                workspaceRoutes.getIfAvailable(() -> WorkspaceEvidenceRoutes.NONE),
                Clock.systemUTC());
    }
}
