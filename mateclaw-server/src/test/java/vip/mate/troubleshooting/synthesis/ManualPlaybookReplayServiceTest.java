package vip.mate.troubleshooting.synthesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.engine.DiagnosisRuleEvaluator;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManualPlaybookReplayServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T03:00:00Z");

    private final TroubleshootingSopPersistenceService candidates =
            mock(TroubleshootingSopPersistenceService.class);
    private final ManualPlaybookReplaySuiteCatalog catalog =
            mock(ManualPlaybookReplaySuiteCatalog.class);
    private final ManualPlaybookReplayAttestationStore store =
            mock(ManualPlaybookReplayAttestationStore.class);
    private final ManualPlaybookReplayFingerprint fingerprints =
            new ManualPlaybookReplayFingerprint(
                    new ObjectMapper().findAndRegisterModules());
    private final ManualPlaybookReplayService service =
            new ManualPlaybookReplayService(
                    candidates,
                    catalog,
                    fingerprints,
                    new ManualPlaybookReplayEvaluator(
                            new CriterionEvaluator(), new DiagnosisRuleEvaluator()),
                    store,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    () -> "attestation-1");

    @Test
    void persistsOneBoundedAttestationForTheExactCandidateAndSuite() {
        SopEntry candidate = candidate();
        ManualPlaybookReplaySuite suite = suite();
        String candidateFingerprint = fingerprints.candidate(candidate);
        String suiteFingerprint = fingerprints.suite(suite);
        when(candidates.findBySopId(7L, candidate.sopId())).thenReturn(candidate);
        when(catalog.find(candidate.routingKey())).thenReturn(Optional.of(
                new ManualPlaybookReplaySuiteCatalog.ResolvedSuite(
                        suite, suiteFingerprint)));
        when(store.find(7L, candidate.sopId(), candidateFingerprint, suiteFingerprint))
                .thenReturn(Optional.empty());
        when(store.saveOrGet(org.mockito.ArgumentMatchers.eq(7L),
                any(ManualPlaybookReplayAttestation.class)))
                .thenAnswer(invocation -> new ManualPlaybookReplayAttestationStore.Stored(
                        invocation.getArgument(1), true));

        ManualPlaybookReplayAttestation result = service.run(
                7L, candidate.sopId(), "reviewer-a");

        assertThat(result.attestationId()).isEqualTo("attestation-1");
        assertThat(result.status())
                .isEqualTo(ManualPlaybookReplayAttestation.Status.PASSED);
        assertThat(result.candidateFingerprint()).isEqualTo(candidateFingerprint);
        assertThat(result.suiteFingerprint()).isEqualTo(suiteFingerprint);
        assertThat(result.positivePassed()).isEqualTo(1);
        assertThat(result.negativeOrAbstainPassed()).isEqualTo(2);
        assertThat(result.executedBy()).isEqualTo("reviewer-a");
        assertThat(result.executedAt()).isEqualTo(NOW);
        assertThat(result.fixtureMode()).isTrue();
    }

    @Test
    void reusesTheExistingExactProofWithoutReplayingTheSuiteAgain() {
        SopEntry candidate = candidate();
        ManualPlaybookReplaySuite suite = suite();
        String candidateFingerprint = fingerprints.candidate(candidate);
        String suiteFingerprint = fingerprints.suite(suite);
        ManualPlaybookReplayAttestation existing = attestation(
                candidate, candidateFingerprint, suite, suiteFingerprint);
        when(candidates.findBySopId(7L, candidate.sopId())).thenReturn(candidate);
        when(catalog.find(candidate.routingKey())).thenReturn(Optional.of(
                new ManualPlaybookReplaySuiteCatalog.ResolvedSuite(
                        suite, suiteFingerprint)));
        when(store.find(7L, candidate.sopId(), candidateFingerprint, suiteFingerprint))
                .thenReturn(Optional.of(existing));

        assertThat(service.run(7L, candidate.sopId(), "reviewer-b"))
                .isEqualTo(existing);

        verify(store, never()).saveOrGet(
                org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void qualificationReturnsOnlyAProofMatchingTheCurrentDoubleFingerprint() {
        SopEntry candidate = candidate();
        ManualPlaybookReplaySuite suite = suite();
        String candidateFingerprint = fingerprints.candidate(candidate);
        String suiteFingerprint = fingerprints.suite(suite);
        ManualPlaybookReplayAttestation existing = attestation(
                candidate, candidateFingerprint, suite, suiteFingerprint);
        when(catalog.find(candidate.routingKey())).thenReturn(Optional.of(
                new ManualPlaybookReplaySuiteCatalog.ResolvedSuite(
                        suite, suiteFingerprint)));
        when(store.find(7L, candidate.sopId(), candidateFingerprint, suiteFingerprint))
                .thenReturn(Optional.of(existing));

        ManualPlaybookReplayQualification qualification =
                service.qualification(7L, candidate);

        assertThat(qualification.candidateFingerprint()).isEqualTo(candidateFingerprint);
        assertThat(qualification.suiteFingerprint()).isEqualTo(suiteFingerprint);
        assertThat(qualification.attestation()).isEqualTo(existing);
    }

    @Test
    void rejectsARepositoryResultWhoseIdentityDoesNotMatchTheRequestedSource() {
        SopEntry candidate = candidate();
        when(candidates.findBySopId(7L, "requested-id")).thenReturn(candidate);

        assertThatThrownBy(() -> service.run(7L, "requested-id", "reviewer-a"))
                .isInstanceOf(vip.mate.exception.MateClawException.class)
                .hasMessageContaining("identity does not match");

        verify(catalog, never()).find(any());
    }

    private ManualPlaybookReplayAttestation attestation(
            SopEntry candidate,
            String candidateFingerprint,
            ManualPlaybookReplaySuite suite,
            String suiteFingerprint) {
        return new ManualPlaybookReplayAttestation(
                "attestation-existing",
                candidate.sopId(),
                candidate.routingKey(),
                candidateFingerprint,
                suite.suiteId(),
                suite.suiteVersion(),
                suiteFingerprint,
                ManualPlaybookReplayAttestation.Status.PASSED,
                1, 1, 2, 2, List.of(), true, "reviewer-a", NOW);
    }

    private SopEntry candidate() {
        return new SopEntry(
                "manual-topology-v1", "sop.v1", "CSDP",
                "scenario:deployment_topology_probe", "network-path",
                "部署拓扑拨测", "网络路径待核查", "network", "网络平台组",
                "candidate", false,
                List.of(new EvidenceRequest(
                        "EV-TOPOLOGY", "synthetic_probe", "只读拨测",
                        requiredTarget(), "-15m", true)),
                List.of(new AnomalyCriterion(
                        "failed_probe_present", "EV-TOPOLOGY", "失败拨测",
                        new Criterion.NumericGte("failed_probe_count", 1))),
                List.of(new DiagnosisRule(
                        "RULE-TOPOLOGY-FAILURE", List.of("failed_probe_present"),
                        "存在失败拨测", "继续核查相邻链路", Confidence.MEDIUM, false)),
                List.of());
    }

    private ManualPlaybookReplaySuite suite() {
        return new ManualPlaybookReplaySuite(
                ManualPlaybookReplaySuite.CONTRACT_VERSION,
                "deployment-topology-probe/v1", 1,
                "csdp:scenario:deployment_topology_probe",
                new ManualPlaybookReplaySuite.RequiredEvidenceRequest(
                        "EV-TOPOLOGY", "synthetic_probe", true, requiredTarget()),
                List.of(
                        replayCase(
                                "positive", ManualPlaybookReplaySuite.Cohort.POSITIVE,
                                ManualPlaybookReplaySuite.Disposition.MATCHED,
                                "RULE-TOPOLOGY-FAILURE", EvidenceStatus.ANOMALY,
                                Map.of("failed_probe_count", 1)),
                        replayCase(
                                "negative",
                                ManualPlaybookReplaySuite.Cohort.NEGATIVE_OR_ABSTAIN,
                                ManualPlaybookReplaySuite.Disposition.EXCLUDED,
                                null, EvidenceStatus.NORMAL,
                                Map.of("failed_probe_count", 0)),
                        replayCase(
                                "abstain",
                                ManualPlaybookReplaySuite.Cohort.NEGATIVE_OR_ABSTAIN,
                                ManualPlaybookReplaySuite.Disposition.ABSTAINED,
                                null, EvidenceStatus.MISSING, Map.of())));
    }

    private ManualPlaybookReplaySuite.ReplayCase replayCase(
            String id,
            ManualPlaybookReplaySuite.Cohort cohort,
            ManualPlaybookReplaySuite.Disposition disposition,
            String ruleId,
            EvidenceStatus status,
            Map<String, Object> observed) {
        return new ManualPlaybookReplaySuite.ReplayCase(
                id, cohort, disposition, ruleId,
                List.of(new ManualPlaybookReplaySuite.ReplayEvidence(
                        "EV-TOPOLOGY", status, observed)));
    }

    private Map<String, Object> requiredTarget() {
        return Map.of(
                "assetType", "deployment_topology",
                "toolKey", "topology_synthetic_probe");
    }
}
