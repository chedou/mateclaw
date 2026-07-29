package vip.mate.troubleshooting.evaluation;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.agent.ApprovedEvidenceSpineCatalog;
import vip.mate.troubleshooting.evidence.EvidenceProperties;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.evidence.EvidenceSpinePlan;
import vip.mate.troubleshooting.evidence.RecordedReplayAdapter;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecordedReplayEvaluationCapabilityServiceTest {

    @Test
    void returnsTheExactApprovedPlanInsteadOfDerivingFieldsFromTheSearchFixture() {
        Fixture fixture = fixture();
        when(fixture.router.canRoute("CSDP", "log_search", "recorded-replay"))
                .thenReturn(true);
        when(fixture.router.canRoute("CSDP", "log_trace_bundle", "recorded-replay"))
                .thenReturn(true);
        when(fixture.adapter.hasCoreFixture(
                "CSDP", "session-svc", "catalog_lookup"))
                .thenReturn(true);

        RecordedReplayEvaluationCapability ready = fixture.service.inspect(7L, "diag-1");
        RecordedReplayEvaluationCapability wrongWorkspace =
                fixture.service.inspect(8L, "diag-1");

        assertThat(ready.available()).isTrue();
        assertThat(ready.reasonCode()).isEqualTo("READY");
        assertThat(ready.scenarioKey()).isEqualTo("approved_replay_scenario");
        assertThat(ready.searchTerm()).isEqualTo("catalog_lookup");
        assertThat(ready.window()).isEqualTo("-30m");
        assertThat(wrongWorkspace.available()).isFalse();
        assertThat(wrongWorkspace.reasonCode()).isEqualTo("SCOPE_NOT_REGISTERED");
    }

    @Test
    void staysDisabledWhenTheAdapterRouteOrExactApprovedFixtureIsMissing() {
        Fixture fixture = fixture();

        assertThat(fixture.service.inspect(7L, "diag-1").reasonCode())
                .isEqualTo("ADAPTER_NOT_READY");

        when(fixture.router.canRoute("CSDP", "log_search", "recorded-replay"))
                .thenReturn(true);
        when(fixture.router.canRoute("CSDP", "log_trace_bundle", "recorded-replay"))
                .thenReturn(true);
        assertThat(fixture.service.inspect(7L, "diag-1").reasonCode())
                .isEqualTo("FIXTURE_NOT_FOUND");
    }

    @Test
    void rejectsAnApprovedScenarioThatCannotEnterTheEvaluationSampleContract() {
        Fixture fixture = fixture();
        when(fixture.router.canRoute("CSDP", "log_search", "recorded-replay"))
                .thenReturn(true);
        when(fixture.router.canRoute("CSDP", "log_trace_bundle", "recorded-replay"))
                .thenReturn(true);
        when(fixture.adapter.hasCoreFixture("CSDP", "session-svc", "catalog_lookup"))
                .thenReturn(true);
        when(fixture.catalog.visibleScenarioKeys(7L, fixture.incident))
                .thenReturn(List.of("Approved_Replay_Scenario"));
        when(fixture.catalog.resolve(7L, fixture.incident, "Approved_Replay_Scenario"))
                .thenReturn(new ApprovedEvidenceSpineCatalog.ApprovedSpinePlan(
                        "Approved_Replay_Scenario",
                        new EvidenceSpinePlan(
                                "SEARCH", "TRACE", "CONTRAST", "catalog_lookup", "-30m"),
                        Set.of("recorded-replay")));

        RecordedReplayEvaluationCapability capability =
                fixture.service.inspect(7L, "diag-1");

        assertThat(capability.available()).isFalse();
        assertThat(capability.reasonCode()).isEqualTo("FIXTURE_TARGET_INVALID");
    }

    private Fixture fixture() {
        EvidenceSourceRouter router = mock(EvidenceSourceRouter.class);
        RecordedReplayAdapter adapter = mock(RecordedReplayAdapter.class);
        EvidenceProperties properties = new EvidenceProperties();
        properties.getSynthesisPreview().setFixtureWorkspaceId(7L);
        properties.getSynthesisPreview().setFixtureServices(
                Map.of("CSDP", List.of("session-svc")));
        ApprovedEvidenceSpineCatalog catalog = mock(ApprovedEvidenceSpineCatalog.class);
        TroubleshootingPersistenceService persistence =
                mock(TroubleshootingPersistenceService.class);
        Diagnosis diagnosis = mock(Diagnosis.class);
        IncidentContext incident = mock(IncidentContext.class);
        when(incident.system()).thenReturn("CSDP");
        when(incident.service()).thenReturn("session-svc");
        when(diagnosis.incident()).thenReturn(incident);
        when(persistence.get(7L, "diag-1"))
                .thenReturn(new StoredDiagnosis(diagnosis, 1, false));
        when(catalog.visibleScenarioKeys(7L, incident))
                .thenReturn(List.of("approved_replay_scenario"));
        when(catalog.resolve(7L, incident, "approved_replay_scenario"))
                .thenReturn(new ApprovedEvidenceSpineCatalog.ApprovedSpinePlan(
                        "approved_replay_scenario",
                        new EvidenceSpinePlan(
                                "SEARCH", "TRACE", "CONTRAST", "catalog_lookup", "-30m"),
                        Set.of("recorded-replay")));
        return new Fixture(
                new RecordedReplayEvaluationCapabilityService(
                        router, adapter, properties, catalog, persistence),
                router,
                adapter,
                catalog,
                incident);
    }

    private record Fixture(
            RecordedReplayEvaluationCapabilityService service,
            EvidenceSourceRouter router,
            RecordedReplayAdapter adapter,
            ApprovedEvidenceSpineCatalog catalog,
            IncidentContext incident) {
    }
}
