package vip.mate.troubleshooting.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import vip.mate.troubleshooting.evidence.GuanceRecordingBatchReadiness;
import vip.mate.troubleshooting.evidence.GuanceRecordingBatchReadinessService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GuanceRecordingBatchControllerTest {

    @Test
    void exposesTheWorkspaceBatchAsAViewerOnlyReadProjection() throws Exception {
        RequireWorkspaceRole role = GuanceRecordingBatchController.class
                .getDeclaredMethod("current", Long.class)
                .getAnnotation(RequireWorkspaceRole.class);
        assertThat(role).isNotNull();
        assertThat(role.value()).isEqualTo("viewer");

        GuanceRecordingBatchReadinessService service =
                mock(GuanceRecordingBatchReadinessService.class);
        when(service.inspect(7L)).thenReturn(readiness());
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SimpleModule longAsString = new SimpleModule();
        longAsString.addSerializer(Long.class, ToStringSerializer.instance);
        longAsString.addSerializer(Long.TYPE, ToStringSerializer.instance);
        mapper.registerModule(longAsString);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new GuanceRecordingBatchController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        mapper))
                .build();

        mvc.perform(get("/api/v2/troubleshooting/evidence/guance/recording-batches/current")
                        .header("X-Workspace-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractVersion")
                        .value(GuanceRecordingBatchReadinessService.CONTRACT_VERSION))
                .andExpect(jsonPath("$.data.workspaceId").value("7"))
                .andExpect(jsonPath("$.data.frozenTargetCount").value(20))
                .andExpect(jsonPath("$.data.executableTargetCount").value(20))
                .andExpect(jsonPath("$.data.readyForOwnerAcceptance").value(true))
                .andExpect(jsonPath("$.data.targets[0].scenarioKey").value("cti-create-session"))
                .andExpect(jsonPath("$.data.targets[0].bindingFingerprint")
                        .value("b".repeat(64)))
                .andExpect(jsonPath("$.data.targets[0].searchTerm").doesNotExist())
                .andExpect(jsonPath("$.data.targets[0].candidateReference").doesNotExist())
                .andExpect(jsonPath("$.data.apiKey").doesNotExist())
                .andExpect(jsonPath("$.data.dql").doesNotExist());

        verify(service).inspect(7L);
    }

    private GuanceRecordingBatchReadiness readiness() {
        return new GuanceRecordingBatchReadiness(
                GuanceRecordingBatchReadinessService.CONTRACT_VERSION,
                "t7-first-" + "a".repeat(24),
                7L,
                "t7-guance-recording-target-catalog.v1",
                "a".repeat(64),
                20,
                20,
                true,
                List.of(new GuanceRecordingBatchReadiness.TargetReadiness(
                        "target-1",
                        "CSDP",
                        "csdp-task",
                        "cti-create-session",
                        "csdp:scenario:cti-create-session",
                        "b".repeat(64),
                        "c".repeat(64),
                        true,
                        List.of())),
                1787068800L,
                List.of());
    }
}
