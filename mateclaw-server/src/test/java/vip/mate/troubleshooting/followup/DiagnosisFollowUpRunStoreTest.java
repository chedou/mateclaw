package vip.mate.troubleshooting.followup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.TroubleshootingDiagnosisFollowUpRunEntity;
import vip.mate.troubleshooting.repository.TroubleshootingDiagnosisFollowUpRunMapper;
import vip.mate.troubleshooting.repository.TroubleshootingDiagnosisMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiagnosisFollowUpRunStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-21T10:30:00Z");

    @Mock private TroubleshootingDiagnosisFollowUpRunMapper mapper;
    @Mock private TroubleshootingDiagnosisMapper diagnosisMapper;
    private DiagnosisFollowUpRunStore store;

    @BeforeEach
    void setUp() {
        store = new DiagnosisFollowUpRunStore(mapper, diagnosisMapper);
    }

    @Test
    void insertsOnlyTheSecretFreeReceipt() {
        when(mapper.insert(org.mockito.ArgumentMatchers.any(
                TroubleshootingDiagnosisFollowUpRunEntity.class))).thenReturn(1);
        DiagnosisFollowUpRun run = run();
        when(diagnosisMapper.lockVersionForFollowUpAppend(9L, "diag-1")).thenReturn(7);

        store.insert(9L, run);

        ArgumentCaptor<TroubleshootingDiagnosisFollowUpRunEntity> row =
                ArgumentCaptor.forClass(TroubleshootingDiagnosisFollowUpRunEntity.class);
        verify(mapper).insert(row.capture());
        assertThat(row.getValue().getWorkspaceId()).isEqualTo(9L);
        assertThat(row.getValue().getDiagnosisId()).isEqualTo("diag-1");
        assertThat(row.getValue().getDiagnosisVersion()).isEqualTo(7);
        assertThat(row.getValue().getRecordedAt())
                .isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        assertThat(row.getValue().toString()).doesNotContain("raw log", "token=");
    }

    @Test
    void listsImmutableReceiptsWithoutInventingSubmittedContent() {
        TroubleshootingDiagnosisFollowUpRunEntity row =
                new TroubleshootingDiagnosisFollowUpRunEntity();
        row.setRunId("follow-up-run-1");
        row.setDiagnosisId("diag-1");
        row.setDiagnosisVersion(7);
        row.setConclusionType("HYPOTHESIS");
        row.setTurnKind("SUPPLEMENTAL_EVIDENCE");
        row.setContentLength(24);
        row.setDisposition("RECORDED_NOT_VERIFIED");
        row.setActorRef("admin");
        row.setRecordedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(mapper.listByDiagnosis(9L, "diag-1")).thenReturn(List.of(row));

        List<DiagnosisFollowUpRun> runs = store.list(9L, "diag-1");

        assertThat(runs).containsExactly(run());
    }

    @Test
    void rejectsTheAppendWhenTheDiagnosisVersionChangedBeforeTheRowLock() {
        when(diagnosisMapper.lockVersionForFollowUpAppend(9L, "diag-1")).thenReturn(8);

        assertThatThrownBy(() -> store.insert(9L, run()))
                .hasMessageContaining("diagnosis changed");
        org.mockito.Mockito.verifyNoInteractions(mapper);
    }

    private DiagnosisFollowUpRun run() {
        return new DiagnosisFollowUpRun(
                "follow-up-run-1",
                "diag-1",
                7,
                ConclusionType.HYPOTHESIS,
                DiagnosisFollowUpIntent.SUPPLEMENTAL_EVIDENCE,
                24,
                DiagnosisFollowUpDisposition.RECORDED_NOT_VERIFIED,
                "admin",
                NOW);
    }
}
