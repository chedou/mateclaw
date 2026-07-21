package vip.mate.troubleshooting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.model.TroubleshootingQueryTemplateEntity;
import vip.mate.troubleshooting.repository.TroubleshootingQueryTemplateMapper;
import vip.mate.troubleshooting.service.TroubleshootingQueryTemplateService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TroubleshootingQueryTemplateServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resolveForAlertSelectsTemplateByMatchJsonBeforeDefaultTemplate() {
        TroubleshootingQueryTemplateMapper mapper = mock(TroubleshootingQueryTemplateMapper.class);
        TroubleshootingQueryTemplateEntity defaultTemplate = template(
                "guance-http-dial-default",
                "",
                true,
                10
        );
        TroubleshootingQueryTemplateEntity malaysiaTemplate = template(
                "guance-http-dial-malaysia",
                """
                        {
                          "labelExists": ["syntheticsTaskName"],
                          "labels": { "region": "malaysia" },
                          "metricNames": ["synthetics_status_code"],
                          "keywords": ["可用性检测"]
                        }
                        """,
                false,
                100
        );
        when(mapper.selectList(any())).thenReturn(List.of(defaultTemplate, malaysiaTemplate));
        TroubleshootingQueryTemplateService service = new TroubleshootingQueryTemplateService(mapper, objectMapper);

        Optional<TroubleshootingQueryTemplateEntity> resolved = service.resolveForAlert(
                1L,
                "guance",
                "synthetics",
                "",
                alert(Map.of(
                        "region", "malaysia",
                        "syntheticsTaskName", "马来-国际CPQ-首页"
                ))
        );

        assertTrue(resolved.isPresent());
        assertEquals("guance-http-dial-malaysia", resolved.get().getTemplateKey());
        verify(mapper, never()).selectOne(any());
    }

    @Test
    void resolveForAlertFallsBackToDefaultWhenNoMatchJsonRuleMatches() {
        TroubleshootingQueryTemplateMapper mapper = mock(TroubleshootingQueryTemplateMapper.class);
        TroubleshootingQueryTemplateEntity singaporeTemplate = template(
                "guance-http-dial-singapore",
                """
                        {
                          "labels": { "region": "singapore" },
                          "metricNames": ["synthetics_status_code"]
                        }
                        """,
                false,
                100
        );
        TroubleshootingQueryTemplateEntity defaultTemplate = template(
                "guance-http-dial-default",
                "",
                true,
                10
        );
        when(mapper.selectList(any())).thenReturn(List.of(singaporeTemplate));
        when(mapper.selectOne(any())).thenReturn(defaultTemplate);
        TroubleshootingQueryTemplateService service = new TroubleshootingQueryTemplateService(mapper, objectMapper);

        Optional<TroubleshootingQueryTemplateEntity> resolved = service.resolveForAlert(
                1L,
                "guance",
                "synthetics",
                null,
                alert(Map.of(
                        "region", "malaysia",
                        "syntheticsTaskName", "马来-国际CPQ-首页"
                ))
        );

        assertTrue(resolved.isPresent());
        assertEquals("guance-http-dial-default", resolved.get().getTemplateKey());
    }

    @Test
    void resolveForAlertCanMatchInfrastructureTemplateByHostName() {
        TroubleshootingQueryTemplateMapper mapper = mock(TroubleshootingQueryTemplateMapper.class);
        TroubleshootingQueryTemplateEntity defaultTemplate = template(
                "guance-host-default",
                "host",
                "",
                true,
                10
        );
        TroubleshootingQueryTemplateEntity cpqNodeTemplate = template(
                "guance-host-cpq-node",
                "host",
                """
                        {
                          "hostNames": ["cpq-node-*"],
                          "keywords": ["cpu"]
                        }
                        """,
                false,
                100
        );
        when(mapper.selectList(any())).thenReturn(List.of(defaultTemplate, cpqNodeTemplate));
        TroubleshootingQueryTemplateService service = new TroubleshootingQueryTemplateService(mapper, objectMapper);

        Optional<TroubleshootingQueryTemplateEntity> resolved = service.resolveForAlert(
                1L,
                "guance",
                "host",
                "",
                new SopRouteRequest(
                        "evt-host",
                        "wecom",
                        "P1",
                        "主机 CPU 使用率过高",
                        "firing",
                        "cpq-web",
                        "prod",
                        "bwx-prod-k8s",
                        "default",
                        null,
                        null,
                        null,
                        "cpu_usage",
                        "cpq-node-01 cpu high",
                        null,
                        Map.of("hostName", "cpq-node-01"),
                        3
                )
        );

        assertTrue(resolved.isPresent());
        assertEquals("guance-host-cpq-node", resolved.get().getTemplateKey());
        verify(mapper, never()).selectOne(any());
    }

    @Test
    void seedGuanceDefaultTemplatesInsertsMissingEvidenceTypeTemplates() {
        TroubleshootingQueryTemplateMapper mapper = mock(TroubleshootingQueryTemplateMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        TroubleshootingQueryTemplateService service = new TroubleshootingQueryTemplateService(mapper, objectMapper);

        List<TroubleshootingQueryTemplateEntity> seeded = service.seedGuanceDefaultTemplates(1L);

        var captor = forClass(TroubleshootingQueryTemplateEntity.class);
        verify(mapper, times(5)).insert(captor.capture());
        assertEquals(5, seeded.size());
        assertEquals(List.of("synthetics", "host", "container", "k8s", "metrics"),
                captor.getAllValues().stream().map(TroubleshootingQueryTemplateEntity::getEvidenceType).toList());
        assertTrue(captor.getAllValues().stream().allMatch(item -> "guance".equals(item.getProvider())));
        assertTrue(captor.getAllValues().stream().allMatch(item -> Integer.valueOf(1).equals(item.getDefaultTemplate())));
        assertTrue(captor.getAllValues().stream().allMatch(item -> item.getPayloadTemplate().contains("\"qtype\": \"dql\"")));
        assertTrue(captor.getAllValues().stream().allMatch(item -> item.getPayloadTemplate().contains("\"slimit\": ${limit}")));
        assertTrue(captor.getAllValues().stream()
                .filter(item -> "metrics".equals(item.getEvidenceType()))
                .findFirst()
                .orElseThrow()
                .getDqlTemplate()
                .contains("${metricNameIdentifier}"));
    }

    private static TroubleshootingQueryTemplateEntity template(String key,
                                                              String matchJson,
                                                              boolean defaultTemplate,
                                                              int priority) {
        return template(key, "synthetics", matchJson, defaultTemplate, priority);
    }

    private static TroubleshootingQueryTemplateEntity template(String key,
                                                              String evidenceType,
                                                              String matchJson,
                                                              boolean defaultTemplate,
                                                              int priority) {
        TroubleshootingQueryTemplateEntity template = new TroubleshootingQueryTemplateEntity();
        template.setWorkspaceId(1L);
        template.setProvider("guance");
        template.setEvidenceType(evidenceType);
        template.setTemplateKey(key);
        template.setName(key);
        template.setPayloadTemplate("""
                {"queries":[{"qtype":"dql","query":{"q":"${dqlQuery}"}}]}
                """);
        template.setDqlTemplate("D::http_dial_testing:(`status_code`, `url`, `name`) { `name` = '${syntheticsTaskNameDql}' }");
        template.setMatchJson(matchJson);
        template.setEnabled(1);
        template.setDefaultTemplate(defaultTemplate ? 1 : 0);
        template.setPriority(priority);
        template.setDeleted(0);
        return template;
    }

    private static SopRouteRequest alert(Map<String, Object> labels) {
        return new SopRouteRequest(
                "evt-1",
                "wecom",
                "P1",
                "马来-国际CPQ-首页",
                "firing",
                "cpq-homepage",
                "prod",
                "bwx-prod-k8s",
                "default",
                null,
                null,
                "/",
                "synthetics_status_code",
                "观测云可用性检测任务失败",
                null,
                labels,
                3
        );
    }
}
