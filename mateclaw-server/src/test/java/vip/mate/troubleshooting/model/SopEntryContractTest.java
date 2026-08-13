package vip.mate.troubleshooting.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The stored shape of a Playbook, which is JSON rather than a Java call.
 *
 * <p>Every Playbook in the registry and the version store is persisted as a
 * serialized aggregate, so a change to this record is a change to data already
 * on disk. Constructing one in Java proves nothing about that: the tests that
 * do so would keep passing while every stored Playbook failed to load.
 */
class SopEntryContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    /**
     * A record with a second constructor can leave Jackson without an
     * unambiguous creator. That failure would not appear in any test that builds
     * the record directly, only when a stored Playbook is read back.
     */
    @Test
    void aStoredPlaybookStillRoundTripsAfterSymptomTriggersWereAdded() throws Exception {
        SopEntry original = playbook(List.of("拨测", "dial test"));

        SopEntry restored = objectMapper.readValue(
                objectMapper.writeValueAsString(original), SopEntry.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.symptomTriggers()).containsExactly("拨测", "dial test");
    }

    /**
     * Playbooks written before the field exists must keep loading, and must load
     * as declaring nothing: silently becoming symptom-routable is the one
     * direction an old contract must not drift in.
     */
    @Test
    void aPlaybookStoredBeforeTheFieldExistedLoadsAsDeclaringNoTrigger() throws Exception {
        String legacyJson = """
                {
                  "sopId": "sop-903001",
                  "contractVersion": "sop.v1",
                  "system": "CSDP",
                  "errorCode": "scenario:deployment_topology_probe",
                  "service": "order-svc",
                  "title": "部署拓扑拨测",
                  "cause": "",
                  "category": "availability",
                  "ownerTeam": "SRE",
                  "status": "approved",
                  "verified": true,
                  "evidenceRequests": [],
                  "anomalyCriteria": [],
                  "diagnosisRules": [],
                  "actions": []
                }
                """;

        SopEntry restored = objectMapper.readValue(legacyJson, SopEntry.class);

        assertThat(restored.symptomTriggers()).isEmpty();
        assertThat(restored.coversSymptom("sf-icare-app-虚机-拨测检测异常")).isFalse();
    }

    @Test
    void aScenarioPlaybookExposesItsKeyWhileAnErrorCodeOneDoesNot() {
        assertThat(playbook(List.of()).scenarioKey())
                .isEqualTo("deployment_topology_probe");

        SopEntry byCode = new SopEntry(
                "sop-903001", null, "CSDP", "903001", "order-svc", "标题",
                "", "", null, "approved", true,
                List.of(), List.of(), List.of(), List.of());

        assertThat(byCode.scenarioScoped()).isFalse();
        assertThat(byCode.scenarioKey()).isNull();
    }

    @Test
    void aTriggerListLongEnoughToHideAMistakeIsRejected() {
        List<String> tooMany = java.util.stream.IntStream.range(0, 17)
                .mapToObj(index -> "trigger-" + index)
                .toList();

        assertThatThrownBy(() -> playbook(tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("16");
    }

    private SopEntry playbook(List<String> triggers) {
        return new SopEntry(
                "sop-topology", "sop.v1", "CSDP",
                "scenario:deployment_topology_probe", "order-svc",
                "部署拓扑拨测", "", "availability", "SRE", "approved", true,
                List.of(), List.of(), List.of(), List.of(), triggers);
    }
}
