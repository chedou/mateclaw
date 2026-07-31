package vip.mate.troubleshooting.synthesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.engine.DiagnosisRuleEvaluator;

import static org.assertj.core.api.Assertions.assertThat;

class ManualPlaybookReplaySuiteCatalogTest {

    @Test
    void loadsTheBundledTopologySuiteWithAnExactStableFingerprint() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ManualPlaybookReplayFingerprint fingerprints =
                new ManualPlaybookReplayFingerprint(objectMapper);
        ManualPlaybookReplaySuiteCatalog catalog =
                new ManualPlaybookReplaySuiteCatalog(
                        objectMapper,
                        fingerprints,
                        new ManualPlaybookReplayEvaluator(
                                new CriterionEvaluator(), new DiagnosisRuleEvaluator()),
                        new ClassPathResource(
                                "troubleshooting/replay/manual-playbook-replay-suites.json"));

        ManualPlaybookReplaySuiteCatalog.ResolvedSuite resolved = catalog.find(
                        "csdp:scenario:deployment_topology_probe")
                .orElseThrow();

        assertThat(resolved.fingerprint()).matches("[a-f0-9]{64}");
        assertThat(resolved.suite().suiteId())
                .isEqualTo("deployment-topology-probe/v1");
        assertThat(resolved.suite().exampleCandidate().sopId())
                .isEqualTo("manual-deployment-topology-probe-v1");
        assertThat(resolved.suite().cases())
                .extracting(ManualPlaybookReplaySuite.ReplayCase::caseId)
                .containsExactly(
                        "failed-probe-positive",
                        "healthy-probe-negative",
                        "probe-unavailable-abstain");
        assertThat(catalog.find("csdp:scenario:unknown")).isEmpty();
    }
}
