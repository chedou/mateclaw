package vip.mate.troubleshooting.deployment;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.TroubleshootingTopologyProbeRunEntity;
import vip.mate.troubleshooting.repository.TroubleshootingDiagnosisMapper;
import vip.mate.troubleshooting.repository.TroubleshootingTopologyProbeRunMapper;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopologyProbeEvidenceRunPersistenceServiceTest {

    private final TroubleshootingDiagnosisMapper diagnosisMapper =
            mock(TroubleshootingDiagnosisMapper.class);
    private final TroubleshootingTopologyProbeRunMapper runMapper =
            mock(TroubleshootingTopologyProbeRunMapper.class);
    private final TroubleshootingPersistenceService persistence =
            mock(TroubleshootingPersistenceService.class);
    private final TopologyProbeEvidenceRunPersistenceService service =
            new TopologyProbeEvidenceRunPersistenceService(
                    diagnosisMapper, runMapper, persistence);

    @Test
    void locksDiagnosisBeforeInsertSoClosureCannotCommitBetweenCheckAndWrite() throws Exception {
        TroubleshootingTopologyProbeRunEntity entity =
                new TroubleshootingTopologyProbeRunEntity();
        when(diagnosisMapper.lockStatusForDependentAppend(7L, "diag-1"))
                .thenReturn("NEEDS_INVESTIGATION");
        when(runMapper.insert(entity)).thenReturn(1);

        service.insertIfDiagnosisOpen(7L, "diag-1", entity, null, 2);

        InOrder order = inOrder(diagnosisMapper, runMapper);
        order.verify(diagnosisMapper).lockStatusForDependentAppend(7L, "diag-1");
        order.verify(runMapper).insert(entity);
        assertThat(TopologyProbeEvidenceRunPersistenceService.class
                .getDeclaredMethod(
                        "insertIfDiagnosisOpen",
                        long.class,
                        String.class,
                        TroubleshootingTopologyProbeRunEntity.class,
                        Diagnosis.class,
                        int.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void rejectsLateEvidenceAfterClosureWhileHoldingTheSameRowLock() {
        TroubleshootingTopologyProbeRunEntity entity =
                new TroubleshootingTopologyProbeRunEntity();
        when(diagnosisMapper.lockStatusForDependentAppend(7L, "diag-closed"))
                .thenReturn("CLOSED");

        assertThatThrownBy(() -> service.insertIfDiagnosisOpen(
                7L, "diag-closed", entity, null, 2))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode())
                        .isEqualTo(409));

        verify(runMapper, never()).insert(entity);
        verify(persistence, never()).update(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(Diagnosis.class),
                org.mockito.ArgumentMatchers.anyInt());
    }

    /**
     * The run row and the conclusion it produced are one fact. If they could
     * commit separately, a crash between them would leave a Diagnosis that has
     * its evidence on disk and still reports that it is waiting for evidence —
     * the exact state this write-back exists to end.
     */
    @Test
    void writesTheRedecidedConclusionUnderTheSameRowLockThatGuardsTheRun() {
        TroubleshootingTopologyProbeRunEntity entity =
                new TroubleshootingTopologyProbeRunEntity();
        Diagnosis advanced = mock(Diagnosis.class);
        when(diagnosisMapper.lockStatusForDependentAppend(7L, "diag-1"))
                .thenReturn("NEEDS_INVESTIGATION");
        when(runMapper.insert(entity)).thenReturn(1);

        service.insertIfDiagnosisOpen(7L, "diag-1", entity, advanced, 4);

        InOrder order = inOrder(diagnosisMapper, runMapper, persistence);
        order.verify(diagnosisMapper).lockStatusForDependentAppend(7L, "diag-1");
        order.verify(runMapper).insert(entity);
        order.verify(persistence).update(7L, advanced, 4);
    }

    @Test
    void aLookAgainRunTouchesNothingButItsOwnHistory() {
        TroubleshootingTopologyProbeRunEntity entity =
                new TroubleshootingTopologyProbeRunEntity();
        when(diagnosisMapper.lockStatusForDependentAppend(7L, "diag-confirmed"))
                .thenReturn("CONFIRMED");
        when(runMapper.insert(entity)).thenReturn(1);

        service.insertIfDiagnosisOpen(7L, "diag-confirmed", entity, null, 9);

        verify(runMapper).insert(entity);
        verify(persistence, never()).update(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(Diagnosis.class),
                org.mockito.ArgumentMatchers.anyInt());
    }
}
