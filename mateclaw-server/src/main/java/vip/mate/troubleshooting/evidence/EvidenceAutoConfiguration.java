package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/** Composes evidence ports and adapters without enabling either source by default. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EvidenceProperties.class)
public class EvidenceAutoConfiguration {

    @Bean
    EvidenceHttpTransport evidenceHttpTransport() {
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
            EvidenceHttpTransport transport) {
        return new GuanceEvidenceAdapter(
                properties.getGuance(), objectMapper, transport, Clock.systemUTC());
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

    @Bean
    EvidenceSourceRouter evidenceSourceRouter(
            List<EvidenceSourceAdapter> adapters,
            EvidenceProperties properties) {
        return new EvidenceSourceRouter(adapters, properties, Clock.systemUTC());
    }
}
