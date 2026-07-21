package vip.mate.troubleshooting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.skill.manifest.SkillManifestParser;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.runtime.SkillFrontmatterParser;
import vip.mate.troubleshooting.model.SopDefinition;
import vip.mate.troubleshooting.service.SopRegistryService;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SopRegistryServiceTest {

    @Test
    void usesCurrentSkillContentBeforeStaleManifestJson() {
        SkillMapper mapper = mock(SkillMapper.class);
        SkillEntity row = new SkillEntity();
        row.setId(1L);
        row.setName("troubleshooting-api-service");
        row.setDescription("API SOP");
        row.setVersion("1.0.0");
        row.setBuiltin(true);
        row.setEnabled(true);
        row.setDeleted(0);
        row.setUpdateTime(LocalDateTime.now());
        row.setManifestJson("""
                {
                  "name": "troubleshooting-api-service",
                  "troubleshooting": {
                    "domain": "api_service",
                    "scenario": "http_5xx_timeout",
                    "requiredEvidence": ["metrics"],
                    "optionalEvidence": ["k8s"]
                  }
                }
                """);
        row.setSkillContent("""
                ---
                name: troubleshooting-api-service
                description: API SOP
                version: 1.0.0
                type: prompt
                category: troubleshooting
                troubleshooting:
                  domain: api_service
                  scenario: http_5xx_timeout
                  requiredEvidence: [metrics]
                  optionalEvidence: [synthetics, k8s, host, container]
                  outputSchema: sop-checklist-v1
                ---
                # API SOP
                """);
        when(mapper.selectList(any())).thenReturn(List.of(row));

        SkillFrontmatterParser frontmatterParser = new SkillFrontmatterParser();
        SopRegistryService registry = new SopRegistryService(
                mapper,
                new SkillManifestParser(frontmatterParser),
                frontmatterParser,
                new ObjectMapper()
        );

        List<SopDefinition> sops = registry.listSops(1L);

        assertEquals(1, sops.size());
        assertEquals(List.of("metrics"), sops.get(0).requiredEvidence());
        assertTrue(sops.get(0).optionalEvidence().contains("host"));
        assertTrue(sops.get(0).optionalEvidence().contains("container"));
    }
}
