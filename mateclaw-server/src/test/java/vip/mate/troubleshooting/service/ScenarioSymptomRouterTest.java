package vip.mate.troubleshooting.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.IncidentImpact;
import vip.mate.troubleshooting.model.SopEntry;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Routing an alert that has no error code.
 *
 * <p>The property under test throughout is that a symptom may only <em>select</em>
 * a Playbook someone already approved for it, never nominate one. Each case that
 * ends in a miss is a case where picking something would have been possible but
 * would have invented authority.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScenarioSymptomRouterTest {

    private static final long WORKSPACE_ID = 1L;
    private static final String SYSTEM = "ICARE";

    @Mock
    private TroubleshootingSopPersistenceService playbooks;

    @Test
    void aDialTestAlertWithNoErrorCodeReachesTheApprovedScenarioPlaybook() {
        SopEntry probe = scenarioPlaybook(
                "deployment_topology_probe", List.of("拨测", "dial test"));
        registerApproved(probe);

        ScenarioSymptomRouter.ScenarioRoute route = router()
                .route(WORKSPACE_ID, incident("sf-icare-app-虚机-拨测检测异常"));

        assertThat(route.matched())
                .as("reason: %s", route.missReason())
                .isTrue();
        assertThat(route.playbook().scenarioKey()).isEqualTo("deployment_topology_probe");
    }

    /**
     * Two owners for one symptom is a registry defect. Choosing between them
     * here would hide it and hand deterministic authority to whichever row the
     * listing happened to return first.
     */
    @Test
    void twoPlaybooksClaimingTheSameSymptomRouteToNeither() {
        registerApproved(
                scenarioPlaybook("deployment_topology_probe", List.of("拨测")),
                scenarioPlaybook("vm_health", List.of("虚机")));

        ScenarioSymptomRouter.ScenarioRoute route = router()
                .route(WORKSPACE_ID, incident("sf-icare-app-虚机-拨测检测异常"));

        assertThat(route.matched()).isFalse();
        assertThat(route.missReason())
                .contains("2")
                .contains("deployment_topology_probe")
                .contains("vm_health");
    }

    @Test
    void aPlaybookThatDeclaredNoTriggerIsNeverReachedBySymptom() {
        registerApproved(scenarioPlaybook("deployment_topology_probe", List.of()));

        ScenarioSymptomRouter.ScenarioRoute route = router()
                .route(WORKSPACE_ID, incident("sf-icare-app-虚机-拨测检测异常"));

        assertThat(route.matched()).isFalse();
        assertThat(route.missReason()).contains("no approved scenario Playbook");
    }

    /**
     * An error-code Playbook is already selected by its code. Letting its text
     * also match would give one contract two ways in, and the symptom way skips
     * the code check.
     */
    @Test
    void anErrorCodePlaybookCannotBeSelectedByText() {
        SopEntry byCode = new SopEntry(
                "sop-903001", null, SYSTEM, "903001", "sf-icare-app",
                "拨测检测异常", "", "", null, "approved", true,
                List.of(), List.of(), List.of(), List.of(), List.of("拨测"));
        registerApproved(byCode);

        ScenarioSymptomRouter.ScenarioRoute route = router()
                .route(WORKSPACE_ID, incident("sf-icare-app-虚机-拨测检测异常"));

        assertThat(route.matched()).isFalse();
    }

    /**
     * The listing is a snapshot. Between it and the read, the version store is
     * the authority on whether the Playbook is still operational.
     */
    @Test
    void aPlaybookDeprecatedSinceTheListingDropsOutRatherThanRouting() {
        SopEntry probe = scenarioPlaybook("deployment_topology_probe", List.of("拨测"));
        when(playbooks.list(eq(WORKSPACE_ID), eq("approved"), eq(SYSTEM), anyInt()))
                .thenReturn(List.of(summaryFor(probe)));
        when(playbooks.find(WORKSPACE_ID, SYSTEM, probe.errorCode())).thenReturn(null);

        ScenarioSymptomRouter.ScenarioRoute route = router()
                .route(WORKSPACE_ID, incident("sf-icare-app-虚机-拨测检测异常"));

        assertThat(route.matched()).isFalse();
    }

    @Test
    void aRegistryFailureDegradesToTheExistingMissPathRatherThanFailingTheReport() {
        when(playbooks.list(eq(WORKSPACE_ID), anyString(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("registry down"));

        ScenarioSymptomRouter.ScenarioRoute route = router()
                .route(WORKSPACE_ID, incident("sf-icare-app-虚机-拨测检测异常"));

        assertThat(route.matched()).isFalse();
        assertThat(route.missReason()).contains("unavailable");
    }

    @Test
    void triggerMatchingIgnoresCaseAndSurroundingWhitespaceInTheDeclaration() {
        registerApproved(scenarioPlaybook(
                "deployment_topology_probe", List.of("  Dial Test  ")));

        ScenarioSymptomRouter.ScenarioRoute route = router()
                .route(WORKSPACE_ID, incident("ICARE dial test failing"));

        assertThat(route.matched())
                .as("reason: %s", route.missReason())
                .isTrue();
    }

    /**
     * A one-character CJK trigger would match a large share of Chinese alert
     * text, so it is dropped rather than quietly becoming a catch-all route.
     */
    @Test
    void aSingleCharacterTriggerIsDiscardedInsteadOfMatchingAlmostEverything() {
        SopEntry probe = scenarioPlaybook(
                "deployment_topology_probe", List.of("测", "拨测"));

        assertThat(probe.symptomTriggers()).containsExactly("拨测");
    }

    private ScenarioSymptomRouter router() {
        return new ScenarioSymptomRouter(playbooks);
    }

    private void registerApproved(SopEntry... entries) {
        when(playbooks.list(eq(WORKSPACE_ID), eq("approved"), eq(SYSTEM), anyInt()))
                .thenReturn(List.of(entries).stream().map(this::summaryFor).toList());
        for (SopEntry entry : entries) {
            when(playbooks.find(WORKSPACE_ID, SYSTEM, entry.errorCode())).thenReturn(entry);
        }
    }

    private SopEntry scenarioPlaybook(String scenarioKey, List<String> triggers) {
        return new SopEntry(
                "sop-" + scenarioKey, null, SYSTEM, "scenario:" + scenarioKey,
                "sf-icare-app", scenarioKey, "", "", null, "approved", true,
                List.of(), List.of(), List.of(), List.of(), triggers);
    }

    private SopSummary summaryFor(SopEntry entry) {
        return new SopSummary(
                entry.sopId(), entry.routingKey(), entry.system(), entry.errorCode(),
                entry.service(), entry.status(), entry.verified(), entry.operational(),
                LocalDateTime.now(), LocalDateTime.now(), 1, null, null, null, null, null);
    }

    private IncidentContext incident(String symptom) {
        return new IncidentContext(
                "INC-1", SYSTEM, "sf-icare-app", null, symptom, "P2",
                IncidentImpact.unknown("未知"), null, Instant.parse("2026-08-12T08:36:00Z"),
                null, "channel:wecom", IncidentCompleteness.STRUCTURED, symptom);
    }
}
