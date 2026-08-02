package vip.mate.troubleshooting.synthesis;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.engine.Criterion;
import vip.mate.troubleshooting.model.AnomalyCriterion;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.DiagnosisRule;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.KnowledgeEvidenceGrade;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultKnowledgePromotionMaterialReaderTest {

    @Test
    void resolvesOnlyTheExactServerOwnedManualCandidate() {
        TroubleshootingSopPersistenceService candidates =
                mock(TroubleshootingSopPersistenceService.class);
        SopEntry candidate = candidate();
        when(candidates.findBySopId(7L, "sop-1")).thenReturn(candidate);
        ManualPlaybookReplaySuiteCatalog catalog =
                mock(ManualPlaybookReplaySuiteCatalog.class);
        when(catalog.evidenceGrade("csdp:903001", candidate))
                .thenReturn(Optional.of(KnowledgeEvidenceGrade.AUTHORED_FIXTURE));
        DefaultKnowledgePromotionMaterialReader reader =
                new DefaultKnowledgePromotionMaterialReader(candidates, catalog);

        KnowledgePromotionMaterial material = reader.find(
                        7L, KnowledgeOrigin.MANUAL, "sop-1")
                .orElseThrow();

        assertThat(material.origin()).isEqualTo(KnowledgeOrigin.MANUAL);
        assertThat(material.sourceRecordId()).isEqualTo("sop-1");
        assertThat(material.selectorKey()).isEqualTo("csdp:903001");
        assertThat(material.evidenceGrade())
                .isEqualTo(KnowledgeEvidenceGrade.AUTHORED_FIXTURE);
        assertThat(material.playbook()).isEqualTo(candidate());
    }

    @Test
    void unsupportedSourceContractsRemainFailClosedWithoutReadingManualRows() {
        TroubleshootingSopPersistenceService candidates =
                mock(TroubleshootingSopPersistenceService.class);
        ManualPlaybookReplaySuiteCatalog catalog =
                mock(ManualPlaybookReplaySuiteCatalog.class);
        DefaultKnowledgePromotionMaterialReader reader =
                new DefaultKnowledgePromotionMaterialReader(candidates, catalog);

        assertThat(reader.find(
                7L, KnowledgeOrigin.EVIDENCE_DERIVED, "record-1")).isEmpty();
        assertThat(reader.find(
                7L, KnowledgeOrigin.OUTCOME_BACKED, "candidate-1")).isEmpty();
        verify(candidates, never()).findBySopId(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void manualCandidateWithoutAServerOwnedEvidenceGradeCannotBePromoted() {
        TroubleshootingSopPersistenceService candidates =
                mock(TroubleshootingSopPersistenceService.class);
        SopEntry candidate = candidate();
        when(candidates.findBySopId(7L, "sop-1")).thenReturn(candidate);
        ManualPlaybookReplaySuiteCatalog catalog =
                mock(ManualPlaybookReplaySuiteCatalog.class);
        when(catalog.evidenceGrade("csdp:903001", candidate))
                .thenReturn(Optional.empty());
        DefaultKnowledgePromotionMaterialReader reader =
                new DefaultKnowledgePromotionMaterialReader(candidates, catalog);

        assertThat(reader.find(7L, KnowledgeOrigin.MANUAL, "sop-1")).isEmpty();
    }

    private SopEntry candidate() {
        return new SopEntry(
                "sop-1", SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP", "903001", "order-svc", "连接池耗尽",
                "连接池打满", "database", "DBA 组", "candidate", false,
                List.of(new EvidenceRequest(
                        "EV-1", "log_count", "确认发生",
                        Map.of("service", "order-svc"), "-15m", true)),
                List.of(new AnomalyCriterion(
                        "error_present", "EV-1", "错误出现",
                        new Criterion.NumericGte("count", 1))),
                List.of(new DiagnosisRule(
                        "R-1", List.of("error_present"), "连接池耗尽",
                        "连接不可用", Confidence.HIGH, false)),
                List.of());
    }
}
