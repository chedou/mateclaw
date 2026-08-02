package vip.mate.troubleshooting.synthesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import vip.mate.troubleshooting.engine.CriterionEvaluator;
import vip.mate.troubleshooting.engine.DiagnosisRuleEvaluator;
import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.model.TroubleshootingPlaybookVersionEntity;
import vip.mate.troubleshooting.repository.TroubleshootingPlaybookVersionMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricalKnowledgeEvidenceGradeReconcilerTest {

    private ObjectMapper objectMapper;
    private ManualPlaybookReplaySuiteCatalog catalog;
    private TroubleshootingPlaybookVersionMapper versions;
    private HistoricalKnowledgeEvidenceGradeReconciler reconciler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        ManualPlaybookReplayFingerprint fingerprints =
                new ManualPlaybookReplayFingerprint(objectMapper);
        catalog = new ManualPlaybookReplaySuiteCatalog(
                objectMapper,
                fingerprints,
                new ManualPlaybookReplayEvaluator(
                        new CriterionEvaluator(), new DiagnosisRuleEvaluator()),
                new ClassPathResource(
                        "troubleshooting/replay/manual-playbook-replay-suites.json"));
        versions = mock(TroubleshootingPlaybookVersionMapper.class);
        reconciler = new HistoricalKnowledgeEvidenceGradeReconciler(
                versions, objectMapper, catalog);
    }

    @Test
    void reconstructsAndUpgradesOnlyAnExactServerOwnedCandidate() throws Exception {
        SopEntry exact = catalog.find("csdp:IM1010").orElseThrow()
                .suite().exampleCandidate();
        TroubleshootingPlaybookVersionEntity forgedSameIdentity = legacyVersion(
                1L, exact, exact.cause() + " (forged)");
        TroubleshootingPlaybookVersionEntity exactVersion = legacyVersion(
                2L, exact, exact.cause());
        when(versions.listUnverifiedKnowledgeEvidenceGradesAfter(
                0L, HistoricalKnowledgeEvidenceGradeReconciler.PAGE_SIZE))
                .thenReturn(List.of(forgedSameIdentity));
        when(versions.listUnverifiedKnowledgeEvidenceGradesAfter(
                1L, HistoricalKnowledgeEvidenceGradeReconciler.PAGE_SIZE))
                .thenReturn(List.of(exactVersion));
        when(versions.listUnverifiedKnowledgeEvidenceGradesAfter(
                2L, HistoricalKnowledgeEvidenceGradeReconciler.PAGE_SIZE))
                .thenReturn(List.of());
        when(versions.backfillKnowledgeEvidenceGrade(
                2L, "RECORDED_AGGREGATE")).thenReturn(1);

        reconciler.run(mock(ApplicationArguments.class));

        verify(versions).backfillKnowledgeEvidenceGrade(
                2L, "RECORDED_AGGREGATE");
        verify(versions, never()).backfillKnowledgeEvidenceGrade(
                1L, "RECORDED_AGGREGATE");
    }

    @Test
    void malformedOrUnknownHistoryRemainsUnverified() {
        TroubleshootingPlaybookVersionEntity malformed =
                new TroubleshootingPlaybookVersionEntity();
        malformed.setId(3L);
        malformed.setSelectorKey("csdp:IM1010");
        malformed.setSourceRecordId("manual-csdp-im1010-v1");
        malformed.setAggregateJson("{}");

        assertThat(reconciler.exactGrade(malformed)).isEmpty();
        verify(versions, never()).backfillKnowledgeEvidenceGrade(
                3L, KnowledgeEvidenceGrade.RECORDED_AGGREGATE.name());
    }

    private TroubleshootingPlaybookVersionEntity legacyVersion(
            long id,
            SopEntry candidate,
            String cause) throws Exception {
        SopEntry approved = new SopEntry(
                "playbook-legacy-" + id,
                candidate.contractVersion(),
                candidate.system(),
                candidate.errorCode(),
                candidate.service(),
                candidate.title(),
                cause,
                candidate.category(),
                candidate.ownerTeam(),
                "approved",
                true,
                candidate.evidenceRequests(),
                candidate.anomalyCriteria(),
                candidate.diagnosisRules(),
                candidate.actions());
        TroubleshootingPlaybookVersionEntity version =
                new TroubleshootingPlaybookVersionEntity();
        version.setId(id);
        version.setSelectorKey(candidate.routingKey());
        version.setSourceRecordId(candidate.sopId());
        version.setAggregateJson(objectMapper.writeValueAsString(approved));
        return version;
    }
}
