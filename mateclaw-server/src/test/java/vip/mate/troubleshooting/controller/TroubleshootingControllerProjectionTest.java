package vip.mate.troubleshooting.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vip.mate.troubleshooting.model.BlastRadius;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.RouteAuthority;
import vip.mate.troubleshooting.model.RouteSemanticsProvenance;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjection;
import vip.mate.troubleshooting.projection.DiagnosisExperienceProjectionService;
import vip.mate.troubleshooting.service.DiagnosisDerivationService;
import vip.mate.troubleshooting.service.DiagnosisSummary;
import vip.mate.troubleshooting.service.InvestigationProvenanceService;
import vip.mate.troubleshooting.service.DiagnosisLifecycleService;
import vip.mate.troubleshooting.service.TroubleshootingIntakeService;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;

import java.util.List;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.http.MediaType.APPLICATION_JSON;

class TroubleshootingControllerProjectionTest {

    @Test
    void exposesTheTypedBusinessAndDeveloperProjection() throws Exception {
        DiagnosisExperienceProjectionService projectionService =
                mock(DiagnosisExperienceProjectionService.class);
        TroubleshootingController controller = new TroubleshootingController(
                mock(TroubleshootingIntakeService.class),
                mock(DiagnosisLifecycleService.class),
                mock(DiagnosisDerivationService.class),
                mock(InvestigationProvenanceService.class),
                mock(TroubleshootingPersistenceService.class),
                projectionService);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        when(projectionService.project(7L, "diag-1")).thenReturn(projection());

        mvc.perform(get("/api/v1/troubleshooting/diagnoses/diag-1/projection")
                        .header("X-Workspace-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businessSummary.conclusionType").value("LOCATED"))
                .andExpect(jsonPath("$.data.businessSummary.fixtureMode").value(true))
                .andExpect(jsonPath("$.data.businessSummary.timings.intakeCost").value("PT2S"))
                .andExpect(jsonPath("$.data.businessSummary.timings.investigateCost").value("PT3S"))
                .andExpect(jsonPath("$.data.developerEvidence.routeSemanticsProvenance")
                        .value("PERSISTED"))
                .andExpect(jsonPath("$.data.developerEvidence.routeAuthority").value("EXPLICIT"))
                .andExpect(jsonPath("$.data.developerEvidence.scenarioAffordances").isEmpty())
                .andExpect(jsonPath("$.data.developerEvidence.investigationTrace.diagnosisId")
                        .value("diag-1"))
                .andExpect(jsonPath("$.data.developerEvidence.investigationTrace.stages.length()")
                        .value(7))
                .andExpect(jsonPath("$.data.developerEvidence.investigationTrace.stages[0].key")
                        .value("INCIDENT"))
                .andExpect(jsonPath("$.data.developerEvidence.investigationTrace.stages[0].summary")
                        .value("未记录"))
                .andExpect(jsonPath("$.data.developerEvidence.contrast.available").value(false));

        verify(projectionService).project(7L, "diag-1");
    }

    @Test
    void reportsUsingTheArrivalTimestampCapturedBeforeRequestMapping() throws Exception {
        TroubleshootingIntakeService intakeService = mock(TroubleshootingIntakeService.class);
        TroubleshootingController controller = new TroubleshootingController(
                intakeService,
                mock(DiagnosisLifecycleService.class),
                mock(DiagnosisDerivationService.class),
                mock(InvestigationProvenanceService.class),
                mock(TroubleshootingPersistenceService.class),
                mock(DiagnosisExperienceProjectionService.class));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        Instant arrivedAt = Instant.parse("2026-07-29T01:02:03Z");

        mvc.perform(post("/api/v1/troubleshooting/incidents")
                        .requestAttr(
                                TroubleshootingRequestTimingFilter.REPORTED_AT_ATTRIBUTE,
                                arrivedAt)
                        .header("X-Workspace-Id", "7")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "incidentId":"inc-arrival",
                                  "system":"CSDP",
                                  "service":"csdp-wechat",
                                  "errorCode":"903001",
                                  "title":"message send failed"
                                }
                                """))
                .andExpect(status().isOk());

        verify(intakeService).report(
                eq(7L),
                argThat(incident -> arrivedAt.equals(incident.occurredAt())),
                eq(List.of()),
                eq(false),
                eq(arrivedAt));
    }

    @Test
    void acceptsStructuredImpactAtTheIncidentHttpBoundary() throws Exception {
        TroubleshootingIntakeService intakeService = mock(TroubleshootingIntakeService.class);
        TroubleshootingController controller = new TroubleshootingController(
                intakeService,
                mock(DiagnosisLifecycleService.class),
                mock(DiagnosisDerivationService.class),
                mock(InvestigationProvenanceService.class),
                mock(TroubleshootingPersistenceService.class),
                mock(DiagnosisExperienceProjectionService.class));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        Instant arrivedAt = Instant.parse("2026-07-29T01:02:03Z");

        mvc.perform(post("/api/v1/troubleshooting/incidents")
                        .requestAttr(
                                TroubleshootingRequestTimingFilter.REPORTED_AT_ATTRIBUTE,
                                arrivedAt)
                        .header("X-Workspace-Id", "7")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {
                                  "incidentId":"inc-impact",
                                  "system":"CSDP",
                                  "service":"csdp-session-service",
                                  "errorCode":"903001",
                                  "title":"message send failed",
                                  "impact":{
                                    "functionScope":"消息发送功能",
                                    "affectedCustomers":2,
                                    "affectedUsers":15,
                                    "blastRadius":"MULTI_CUSTOMER",
                                    "evidenceRefs":["EV-IMPACT"],
                                    "observedAt":"2026-07-29T01:02:03Z",
                                    "note":"同窗口两个客户出现同类失败"
                                  }
                                }
                                """))
                .andExpect(status().isOk());

        verify(intakeService).report(
                eq(7L),
                argThat(incident ->
                        incident.impact().affectedCustomers() == 2
                                && incident.impact().affectedUsers() == 15
                                && incident.impact().blastRadius() == BlastRadius.MULTI_CUSTOMER
                                && incident.impact().evidenceRefs().equals(List.of("EV-IMPACT"))
                                && arrivedAt.equals(incident.impact().observedAt())),
                eq(List.of()),
                eq(false),
                eq(arrivedAt));
    }

    @Test
    void forwardsTypedInvestigationModeFilterToIndexedQueueList() throws Exception {
        TroubleshootingPersistenceService persistence = mock(TroubleshootingPersistenceService.class);
        when(persistence.list(7L, null, null, InvestigationMode.SCENARIO_PLAYBOOK, 50))
                .thenReturn(List.of(new DiagnosisSummary(
                        "diag-1",
                        "case-1",
                        "CSDP",
                        "903001",
                        "csdp-wechat",
                        DiagnosisStatus.READY_FOR_HUMAN.name(),
                        InvestigationMode.SCENARIO_PLAYBOOK,
                        RouteAuthority.RULE_MATCHED,
                        RouteSemanticsProvenance.PERSISTED,
                        false,
                        null,
                        3,
                        null,
                        null)));
        TroubleshootingController controller = new TroubleshootingController(
                mock(TroubleshootingIntakeService.class),
                mock(DiagnosisLifecycleService.class),
                mock(DiagnosisDerivationService.class),
                mock(InvestigationProvenanceService.class),
                persistence,
                mock(DiagnosisExperienceProjectionService.class));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();

        mvc.perform(get("/api/v1/troubleshooting/diagnoses")
                        .header("X-Workspace-Id", "7")
                        .param("investigationMode", "SCENARIO_PLAYBOOK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].investigationMode").value("SCENARIO_PLAYBOOK"))
                .andExpect(jsonPath("$.data[0].routeSemanticsProvenance").value("PERSISTED"));

        verify(persistence).list(7L, null, null, InvestigationMode.SCENARIO_PLAYBOOK, 50);
    }

    private DiagnosisExperienceProjection projection() {
        DiagnosisExperienceProjection.ImpactView impact =
                new DiagnosisExperienceProjection.ImpactView(
                        "订单创建", null, null,
                        BlastRadius.UNKNOWN,
                        List.of(), null, "影响人数未测量");
        DiagnosisExperienceProjection.BusinessSummary business =
                new DiagnosisExperienceProjection.BusinessSummary(
                        "diag-1", ConclusionType.LOCATED,
                        "已定位异常环节", "确定性判据已命中。", Confidence.HIGH,
                        "订单创建超时", impact,
                        new DiagnosisExperienceProjection.NextStep(
                                "定位结果", "请开发复核", "平台不执行生产变更"),
                        DiagnosisStatus.READY_FOR_HUMAN,
                        NorthStarTimings.concluded(
                                Instant.parse("2026-07-29T01:00:00Z"),
                                Instant.parse("2026-07-29T01:00:02Z"),
                                Instant.parse("2026-07-29T01:00:05Z")),
                        true);
        DiagnosisExperienceProjection.DeveloperEvidenceView developer =
                new DiagnosisExperienceProjection.DeveloperEvidenceView(
                        "diag-1",
                        InvestigationMode.ERROR_CODE_PLAYBOOK,
                        RouteAuthority.EXPLICIT,
                        RouteSemanticsProvenance.PERSISTED,
                        "csdp:903001",
                        List.of(),
                        new DiagnosisExperienceProjection.CallChainView(
                                null, List.of(), "未关联调用链", impact.blastRadius()),
                        List.of(),
                        new DiagnosisExperienceProjection.ContrastView(
                                false, null, null, null, "未取得成功样本", List.of()),
                        new DiagnosisExperienceProjection.DraftView(
                                null, "尚无草稿", List.of(), "尚未闭环",
                                DiagnosisExperienceProjection.ReviewStatus.DRAFT,
                                "不会自动发布"),
                        List.of("平台不执行生产变更"),
                        true);
        return new DiagnosisExperienceProjection(business, developer);
    }
}
