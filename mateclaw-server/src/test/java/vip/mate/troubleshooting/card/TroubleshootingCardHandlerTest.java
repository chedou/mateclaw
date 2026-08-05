package vip.mate.troubleshooting.card;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.service.DiagnosisLifecycleService;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A chat card may nudge the lifecycle forward, but it must never become a way
 * around identity or a way to reach a transition that needs more context than a
 * card can carry.
 */
@ExtendWith(MockitoExtension.class)
class TroubleshootingCardHandlerTest {

    private static final long WORKSPACE_ID = 1L;
    private static final String DIAGNOSIS_ID = "diag-1";
    private static final String OPEN_ID = "ou_abc123";

    @Mock
    private DiagnosisLifecycleService lifecycleService;

    @Mock
    private CardOperatorResolver operatorResolver;

    private TroubleshootingCardHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TroubleshootingCardHandler(lifecycleService, operatorResolver);
    }

    @Test
    void confirmsUnderTheLinkedMateClawUserRatherThanTheChatId() {
        when(operatorResolver.resolveFeishu(OPEN_ID)).thenReturn(Optional.of("alice"));

        var outcome = handler.handle(WORKSPACE_ID, confirmValue(), OPEN_ID);

        assertThat(outcome.stateChanged()).isTrue();
        verify(lifecycleService).confirm(WORKSPACE_ID, DIAGNOSIS_ID, "alice");
    }

    @Test
    void refusesAClickFromAChatAccountWithNoLinkedUser() {
        when(operatorResolver.resolveFeishu(OPEN_ID)).thenReturn(Optional.empty());

        var outcome = handler.handle(WORKSPACE_ID, confirmValue(), OPEN_ID);

        assertThat(outcome.handled()).isTrue();
        assertThat(outcome.stateChanged())
                .as("an unattributable click must not move the lifecycle")
                .isFalse();
        assertThat(outcome.toast()).contains("未关联");
        verifyNoInteractions(lifecycleService);
    }

    @Test
    void reportsRatherThanSwallowsARefusalFromTheStateMachine() {
        when(operatorResolver.resolveFeishu(OPEN_ID)).thenReturn(Optional.of("alice"));
        doThrow(new MateClawException("err.troubleshooting.workflow_conflict", 409,
                "abstained diagnosis requires new evidence before confirmation"))
                .when(lifecycleService).confirm(anyLong(), anyString(), anyString());

        var outcome = handler.handle(WORKSPACE_ID, confirmValue(), OPEN_ID);

        assertThat(outcome.handled()).isTrue();
        assertThat(outcome.stateChanged()).isFalse();
        assertThat(outcome.toast()).contains("未生效");
    }

    @Test
    void sendsTheOperatorToTheConsoleWhenTheCardOnlyOffersToOpenTheCase() {
        var outcome = handler.handle(WORKSPACE_ID,
                new TroubleshootingCardAction(
                        TroubleshootingCardAction.Verb.OPEN, DIAGNOSIS_ID).encode(),
                OPEN_ID);

        assertThat(outcome.stateChanged()).isFalse();
        assertThat(outcome.toast()).contains("工作台");
        verifyNoInteractions(lifecycleService, operatorResolver);
    }

    @Test
    void ignoresAPayloadThatIsNotOurs() {
        var outcome = handler.handle(WORKSPACE_ID,
                Map.of("action", "tg_approval.approve", "rid", "pending-1"), OPEN_ID);

        assertThat(outcome.handled())
                .as("another card kind's payload must fall through untouched")
                .isFalse();
        verify(lifecycleService, never()).confirm(anyLong(), any(), any());
    }

    @Test
    void ignoresAPayloadMissingTheDiagnosisId() {
        var outcome = handler.handle(WORKSPACE_ID, Map.of("action", "ts.confirm"), OPEN_ID);

        assertThat(outcome.handled()).isFalse();
        verifyNoInteractions(lifecycleService);
    }

    @Test
    void roundTripsAButtonValue() {
        var original = new TroubleshootingCardAction(
                TroubleshootingCardAction.Verb.CONFIRM, DIAGNOSIS_ID);

        var decoded = TroubleshootingCardAction.decode(original.encode());

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void keepsItsPrefixDisjointFromToolGuardSoClicksCannotCross() {
        assertThat(TroubleshootingCardAction.Verb.CONFIRM.action()).startsWith("ts.");
        assertThat(TroubleshootingCardAction.decode(
                Map.of("action", "tg_approval.approve", "did", DIAGNOSIS_ID))).isNull();
    }

    private Map<String, Object> confirmValue() {
        return new TroubleshootingCardAction(
                TroubleshootingCardAction.Verb.CONFIRM, DIAGNOSIS_ID).encode();
    }
}
