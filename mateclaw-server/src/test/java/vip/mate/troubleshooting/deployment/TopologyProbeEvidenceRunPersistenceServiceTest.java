package vip.mate.troubleshooting.deployment;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.TroubleshootingTopologyProbeRunEntity;
import vip.mate.troubleshooting.repository.TroubleshootingDiagnosisMapper;
import vip.mate.troubleshooting.repository.TroubleshootingTopologyProbeRunMapper;

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
    private final TopologyProbeEvidenceRunPersistenceService service =
            new TopologyProbeEvidenceRunPersistenceService(diagnosisMapper, runMapper);

    @Test
    void locksDiagnosisBeforeInsertSoClosureCannotCommitBetweenCheckAndWrite() throws Exception {
        TroubleshootingTopologyProbeRunEntity entity =
                new TroubleshootingTopologyProbeRunEntity();
        when(diagnosisMapper.lockStatusForDependentAppend(7L, "diag-1"))
                .thenReturn("NEEDS_INVESTIGATION");
        when(runMapper.insert(entity)).thenReturn(1);

        service.insertIfDiagnosisOpen(7L, "diag-1", entity);

        InOrder order = inOrder(diagnosisMapper, runMapper);
        order.verify(diagnosisMapper).lockStatusForDependentAppend(7L, "diag-1");
        order.verify(runMapper).insert(entity);
        assertThat(TopologyProbeEvidenceRunPersistenceService.class
                .getDeclaredMethod(
                        "insertIfDiagnosisOpen",
                        long.class,
                        String.class,
                        TroubleshootingTopologyProbeRunEntity.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void rejectsLateEvidenceAfterClosureWhileHoldingTheSameRowLock() {
        TroubleshootingTopologyProbeRunEntity entity =
                new TroubleshootingTopologyProbeRunEntity();
        when(diagnosisMapper.lockStatusForDependentAppend(7L, "diag-closed"))
                .thenReturn("CLOSED");

        assertThatThrownBy(() -> service.insertIfDiagnosisOpen(
                7L, "diag-closed", entity))
                .isInstanceOf(MateClawException.class)
                .satisfies(error -> assertThat(((MateClawException) error).getCode())
                        .isEqualTo(409));

        verify(runMapper, never()).insert(entity);
    }
}
