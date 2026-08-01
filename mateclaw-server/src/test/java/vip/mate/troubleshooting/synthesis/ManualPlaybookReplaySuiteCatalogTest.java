package vip.mate.troubleshooting.synthesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.engine.DiagnosisRuleEvaluator;
import vip.mate.troubleshooting.model.ActionType;
import vip.mate.troubleshooting.model.RecommendedAction;

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

    @Test
    void generatesTheIm1010SuiteFromRecordedAggregateEvidence() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ManualPlaybookReplaySuiteCatalog catalog =
                new ManualPlaybookReplaySuiteCatalog(
                        objectMapper,
                        new ManualPlaybookReplayFingerprint(objectMapper),
                        new ManualPlaybookReplayEvaluator(
                                new CriterionEvaluator(), new DiagnosisRuleEvaluator()),
                        new ClassPathResource(
                                "troubleshooting/replay/manual-playbook-replay-suites.json"));

        ManualPlaybookReplaySuiteCatalog.ResolvedSuite resolved = catalog.find("csdp:IM1010")
                .orElseThrow();

        assertThat(catalog.rejectedSeeds()).isEmpty();
        assertThat(resolved.suite().suiteId()).isEqualTo("csdp-im1010-message-send/v1");
        assertThat(resolved.suite().exampleCandidate().service()).isEqualTo("csp-rpc-msg");
        assertThat(resolved.suite().cases())
                .extracting(ManualPlaybookReplaySuite.ReplayCase::expectedDisposition)
                .containsExactly(
                        ManualPlaybookReplaySuite.Disposition.MATCHED,
                        ManualPlaybookReplaySuite.Disposition.EXCLUDED,
                        ManualPlaybookReplaySuite.Disposition.ABSTAINED);
        assertThat(resolved.suite().exampleCandidate().actions())
                .extracting(RecommendedAction::actionType)
                .allMatch(type -> type == ActionType.AUTO_READONLY
                        || type == ActionType.HUMAN_CONTACT);
    }

    @Test
    void quarantinesOneInvalidRecordedSeedWithoutRemovingFixedSuites() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ObjectNode document;
        try (var input = new ClassPathResource(
                "troubleshooting/replay/manual-playbook-replay-suites.json")
                .getInputStream()) {
            document = (ObjectNode) objectMapper.readTree(input);
        }
        document.put("version", 2);
        ArrayNode recordedSeeds = document.putArray("recordedEvidenceSeeds");
        recordedSeeds.addObject()
                .put("contractVersion", "invalid-recorded-seed")
                .put("selectorKey", "csdp:BROKEN");

        ManualPlaybookReplayFingerprint fingerprints =
                new ManualPlaybookReplayFingerprint(objectMapper);
        ManualPlaybookReplaySuiteCatalog catalog =
                new ManualPlaybookReplaySuiteCatalog(
                        objectMapper,
                        fingerprints,
                        new ManualPlaybookReplayEvaluator(
                                new CriterionEvaluator(), new DiagnosisRuleEvaluator()),
                        new ByteArrayResource(objectMapper.writeValueAsBytes(document)));

        assertThat(catalog.find("csdp:903001")).isPresent();
        assertThat(catalog.rejectedSeeds())
                .containsExactly(new ManualPlaybookReplaySuiteCatalog.RejectedSeed(
                        "csdp:BROKEN", "INVALID_RECORDED_EVIDENCE_SEED"));
    }
}
