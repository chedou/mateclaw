package vip.mate.troubleshooting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.skill.manifest.SkillManifest;
import vip.mate.troubleshooting.dto.SopEvidenceCollectRequest;
import vip.mate.troubleshooting.dto.SopEvidenceCollectResponse;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.dto.SopStepResult;
import vip.mate.troubleshooting.evidence.CollectedEvidence;
import vip.mate.troubleshooting.evidence.EvidenceCollectionRequest;
import vip.mate.troubleshooting.evidence.EvidenceConnector;
import vip.mate.troubleshooting.model.SopDefinition;
import vip.mate.troubleshooting.model.TroubleshootingEvidenceEntity;
import vip.mate.troubleshooting.model.TroubleshootingSopRunEntity;
import vip.mate.troubleshooting.repository.TroubleshootingEvidenceMapper;
import vip.mate.troubleshooting.repository.TroubleshootingSopRunMapper;
import vip.mate.troubleshooting.service.SopEvidenceCollectionService;
import vip.mate.troubleshooting.service.SopRegistryService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SopEvidenceCollectionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void putsGuanceSyntheticsSignalsIntoChecklistDraftAndReport() throws Exception {
        TroubleshootingSopRunMapper runMapper = mock(TroubleshootingSopRunMapper.class);
        TroubleshootingEvidenceMapper evidenceMapper = mock(TroubleshootingEvidenceMapper.class);
        SopRegistryService registryService = mock(SopRegistryService.class);
        SopEvidenceCollectionService service = new SopEvidenceCollectionService(
                runMapper,
                evidenceMapper,
                registryService,
                List.of(new FakeGuanceSyntheticsConnector()),
                objectMapper
        );
        TroubleshootingSopRunEntity run = run(alert());
        SopDefinition sop = sop();
        when(runMapper.selectById(123L)).thenReturn(run);
        when(registryService.findBySkillId(1L, 88L)).thenReturn(Optional.of(sop));

        SopEvidenceCollectResponse response = service.collectForRun(
                1L,
                123L,
                new SopEvidenceCollectRequest(List.of("synthetics"), false)
        );

        assertEquals(1, response.evidenceRecords().size());
        assertEquals(1, response.stepResults().size());
        SopStepResult step = response.stepResults().get(0);
        assertEquals("collect-synthetics", step.stepId());
        assertEquals("passed", step.status());
        assertTrue(step.observation().contains("观测云拨测发现 2 条失败"));
        assertTrue(step.observation().contains("失败率=66.67%"));
        assertTrue(step.observation().contains("失败状态码=[503]"));
        assertTrue(step.interpretation().contains("拨测归一化结论"));
        assertTrue(step.interpretation().contains("受影响节点=[beijing]"));
        assertTrue(response.finalReportTemplate().get("evidenceSignals").toString()
                .contains("观测云拨测发现 2 条失败"));
        verify(evidenceMapper).insert(any(TroubleshootingEvidenceEntity.class));
    }

    @Test
    void putsGuanceInfrastructureSignalsIntoChecklistDraftAndReport() throws Exception {
        TroubleshootingSopRunMapper runMapper = mock(TroubleshootingSopRunMapper.class);
        TroubleshootingEvidenceMapper evidenceMapper = mock(TroubleshootingEvidenceMapper.class);
        SopRegistryService registryService = mock(SopRegistryService.class);
        SopEvidenceCollectionService service = new SopEvidenceCollectionService(
                runMapper,
                evidenceMapper,
                registryService,
                List.of(new FakeGuanceHostConnector()),
                objectMapper
        );
        TroubleshootingSopRunEntity run = run(alert());
        SopDefinition sop = sop();
        when(runMapper.selectById(123L)).thenReturn(run);
        when(registryService.findBySkillId(1L, 88L)).thenReturn(Optional.of(sop));

        SopEvidenceCollectResponse response = service.collectForRun(
                1L,
                123L,
                new SopEvidenceCollectRequest(List.of("host"), false)
        );

        SopStepResult step = response.stepResults().get(0);
        assertEquals("collect-host", step.stepId());
        assertEquals("passed", step.status());
        assertTrue(step.observation().contains("观测云主机发现 1 条异常记录"));
        assertTrue(step.observation().contains("资源压力=[cpq-node-01 cpu_usage=91.2%]"));
        assertTrue(step.interpretation().contains("基础设施异常信号"));
        assertTrue(response.finalReportTemplate().get("evidenceSignals").toString()
                .contains("运行环境或基础设施异常判断"));
        verify(evidenceMapper).insert(any(TroubleshootingEvidenceEntity.class));
    }

    private TroubleshootingSopRunEntity run(SopRouteRequest alert) throws Exception {
        TroubleshootingSopRunEntity run = new TroubleshootingSopRunEntity();
        run.setId(123L);
        run.setWorkspaceId(1L);
        run.setCaseId("case-guance-synthetics");
        run.setSopSkillId(88L);
        run.setAlertJson(objectMapper.writeValueAsString(alert));
        run.setDeleted(0);
        return run;
    }

    private static SopRouteRequest alert() {
        return new SopRouteRequest(
                "evt-1",
                "wecom",
                "P1",
                "观测云可用性检测任务失败",
                "firing",
                "cpq-homepage",
                "prod",
                "bwx-prod-k8s",
                "default",
                null,
                null,
                "/",
                "synthetics_status_code",
                "观测云拨测状态码异常",
                null,
                Map.of("syntheticsTaskName", "马来-国际CPQ-首页"),
                3
        );
    }

    private static SopDefinition sop() {
        return new SopDefinition(
                88L,
                "guance-synthetics-availability",
                null,
                "1.0.0",
                true,
                1L,
                "api_service",
                "http_5xx_timeout",
                SkillManifest.TroubleshootingMatch.builder().build(),
                List.of("synthetics"),
                List.of("metrics", "logs"),
                "sop-checklist-v1",
                "platform-sre",
                90,
                null,
                false,
                "",
                ""
        );
    }

    private static final class FakeGuanceSyntheticsConnector implements EvidenceConnector {
        @Override
        public boolean supports(String evidenceType) {
            return "synthetics".equalsIgnoreCase(evidenceType);
        }

        @Override
        public List<CollectedEvidence> collect(EvidenceCollectionRequest request) {
            return List.of(new CollectedEvidence(
                    "synthetics",
                    "guance",
                    "collected",
                    "观测云拨测结果",
                    "Guance synthetics collected 3 checks, failed=2.",
                    Map.of(
                            "normalized", Map.of(
                                    "availabilityConclusion", "观测云拨测发现 2 条失败，失败状态码 [503]，受影响区域/节点 [beijing]；该证据支持外部入口或可用性受影响判断。",
                                    "diagnosisSignals", List.of(
                                            "拨测样本=3",
                                            "失败率=66.67%",
                                            "失败状态码=[503]",
                                            "受影响节点=[beijing]"
                                    )
                            )
                    )
            ));
        }
    }

    private static final class FakeGuanceHostConnector implements EvidenceConnector {
        @Override
        public boolean supports(String evidenceType) {
            return "host".equalsIgnoreCase(evidenceType);
        }

        @Override
        public List<CollectedEvidence> collect(EvidenceCollectionRequest request) {
            return List.of(new CollectedEvidence(
                    "host",
                    "guance-infrastructure",
                    "collected",
                    "观测云主机快照",
                    "Guance host collected 2 records, abnormal=1.",
                    Map.of(
                            "normalized", Map.of(
                                    "infrastructureConclusion", "观测云主机发现 1 条异常记录，异常对象 [cpq-node-01]，资源压力 [cpq-node-01 cpu_usage=91.2%]；该证据支持运行环境或基础设施异常判断。",
                                    "infrastructureSignals", List.of(
                                            "主机样本=2",
                                            "异常对象=1/2",
                                            "资源压力=[cpq-node-01 cpu_usage=91.2%]"
                                    ),
                                    "abnormalStates", List.of("cpq-node-01 status=high_cpu restarts=null reason=null")
                            )
                    )
            ));
        }
    }
}
