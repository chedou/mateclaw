package vip.mate.troubleshooting.intake;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.agent.TroubleshootingAgentTriageService;
import vip.mate.troubleshooting.evidence.EvidenceSourceRouter;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.RouteMode;
import vip.mate.troubleshooting.service.DeterministicDiagnosisService;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingIntakeService;
import vip.mate.troubleshooting.service.TroubleshootingSopPersistenceService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Walks the head of the blueprint's first scenario (§11.1) as one chain.
 *
 * <p><b>What was missing.</b> Every segment of this chain had unit tests, and
 * the wiring was correct, but nothing joined them. The consequence was specific
 * and easy to miss: the north star's first stage — 补问成本, the time a
 * 服务经理 spends dragging missing facts out of a reporter — <b>had never been
 * produced end to end</b>. Every {@code intakeCost} assertion in the suite used
 * a hand-built constant (30s, 1min, PT2S), and the one session-based test fed a
 * first message that was already complete, so {@code readyAt == reportedAt} and
 * the span was structurally zero.</p>
 *
 * <p>A metric that is only ever asserted against constants is not measured. This
 * test is the one place where the number comes out of the machinery instead of
 * out of a literal.</p>
 *
 * <p><b>Why the chain starts below HTTP.</b> Intake sessions are reachable only
 * through the channel adapter, by design (D17: 不新建入站). So the walk happens
 * where the chain actually begins rather than through an endpoint invented to
 * make it demonstrable.</p>
 */
@ExtendWith(MockitoExtension.class)
class FirstScenarioIntakeChainTest {

    private static final long WORKSPACE_ID = 7L;
    /** When the 服务经理 first says "消息发不出去" and nothing else. */
    private static final Instant REPORTED_AT = Instant.parse("2026-08-01T02:00:00Z");
    /** When, after being asked, they finally supply the missing facts. */
    private static final Instant READY_AT = REPORTED_AT.plus(Duration.ofMinutes(3));
    private static final Duration REAL_INTAKE_COST = Duration.ofMinutes(3);

    @Mock
    private TroubleshootingSopPersistenceService sopPersistence;
    @Mock
    private DeterministicDiagnosisService diagnosisService;
    @Mock
    private EvidenceSourceRouter evidenceRouter;
    @Mock
    private TroubleshootingAgentTriageService agentTriageService;

    private final IntakeSessionReducer reducer = new IntakeSessionReducer();

    @Test
    @DisplayName("不完整报障先进入补问，而不是猜字段")
    void anIncompleteReportIsHeldForFollowUpInsteadOfGuessed() {
        IntakeSession awaiting = reducer.start("intake-first-scenario",
                message("msg-1", "会话消息发送失败，客户说发不出去", REPORTED_AT));

        assertThat(awaiting.status()).isEqualTo(IntakeSessionStatus.AWAITING_INPUT);
        assertThat(awaiting.readyAt())
                .as("还没补齐就不该有 readyAt，否则补问成本会被算成 0")
                .isNull();
        assertThat(awaiting.missingFields())
                .as("缺什么必须说清楚，不能替报障人填")
                .contains("system", "service");
        assertThat(awaiting.system()).isNull();
        assertThat(awaiting.service()).isNull();
        assertThat(IntakeDecision.from(awaiting, false, false).prompt())
                .contains("还需要");
    }

    /**
     * The chain, joined: incomplete report → follow-up → READY → investigation,
     * with the two north-star timestamps carried through unchanged.
     */
    @Test
    @DisplayName("补问补齐后，真实的补问跨度被原样带进调查，而不是被现在时间覆盖")
    void theRealFollowUpSpanReachesTheInvestigation() {
        IntakeSession awaiting = reducer.start("intake-first-scenario",
                message("msg-1", "会话消息发送失败，客户说发不出去", REPORTED_AT));
        IntakeSession ready = reducer.accept(awaiting, message("msg-2", """
                系统: CSDP
                服务: csdp-session-service
                客户ID: tenant-42
                发生时间: 2026-08-01 09:58:00
                """, READY_AT));

        assertThat(ready.status()).isEqualTo(IntakeSessionStatus.READY);
        assertThat(ready.reportedAt()).isEqualTo(REPORTED_AT);
        assertThat(ready.readyAt()).isEqualTo(READY_AT);
        assertThat(Duration.between(ready.reportedAt(), ready.readyAt()))
                .as("补问跨度必须是真实的三分钟，不是 0")
                .isEqualTo(REAL_INTAKE_COST);

        // No error code was ever supplied — this is the §11.1 miss path.
        assertThat(ready.errorCode()).isNull();

        StoredDiagnosis stored = new StoredDiagnosis(placeholderDiagnosis(), 0, true);
        when(agentTriageService.triageForIntake(
                anyLong(), any(), any(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(stored);

        StoredDiagnosis result = intakeService().report(ready);

        assertThat(result).isSameAs(stored);
        ArgumentCaptor<IncidentContext> incident = ArgumentCaptor.forClass(IncidentContext.class);
        verify(agentTriageService).triageForIntake(
                eq(WORKSPACE_ID),
                incident.capture(),
                eq(List.of()),
                eq(false),
                any(),
                eq(REPORTED_AT),
                eq(READY_AT),
                eq("intake-first-scenario"));
        assertThat(incident.getValue().errorCode())
                .as("无错误码的报障不得被补上一个猜来的码")
                .isNull();
        assertThat(incident.getValue().intakeSource()).isEqualTo("channel:wecom");
    }

    /**
     * The end of the chain in the <em>default</em> deployment, pinned.
     *
     * <p>With the miss-path Agent switched off — which is the default and the
     * conservative choice — the blueprint's first scenario cannot produce a
     * diagnosis at all. This is not an oversight in the routing code: the
     * {@code Diagnosis} contract offers exactly two shapes, {@code DETERMINISTIC
     * → ERROR_CODE_PLAYBOOK} (which requires an errorCode) and {@code
     * LLM_FALLBACK → OPEN_DISCOVERY + MODEL_PROPOSED} (which requires a model
     * proposal). There is no shape for "no route matched and no model was
     * consulted", so there is nothing legal to persist.</p>
     *
     * <p>Failing closed here is right — A6 says an incomplete answer beats an
     * invented one, and the reporter is told through the terminal notification
     * rather than left hanging. What this test pins is that the refusal is
     * <em>specific</em>: it names the disabled Agent instead of surfacing a
     * generic error, so an operator reading it knows the fix is a deployment
     * decision and not a defect.</p>
     */
    @Test
    @DisplayName("Agent 关闭时，无码报障 fail-closed 且说明原因，而不是抛一个泛化错误")
    void withoutTheMissPathAgentTheFirstScenarioFailsClosedWithAReason() {
        IntakeSession ready = reducer.accept(
                reducer.start("intake-first-scenario",
                        message("msg-1", "会话消息发送失败", REPORTED_AT)),
                message("msg-2", """
                        系统: CSDP
                        服务: csdp-session-service
                        客户ID: tenant-42
                        发生时间: 2026-08-01 09:58:00
                        """, READY_AT));

        TroubleshootingIntakeService withoutAgent = new TroubleshootingIntakeService(
                sopPersistence, diagnosisService, evidenceRouter, null);

        assertThatThrownBy(() -> withoutAgent.report(ready))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("disabled")
                .as("拒绝必须说清是「这条路没开」，而不是含糊的失败")
                .hasMessageContaining("Agent");
    }

    /**
     * Built with the production constructor, so the service runs on the real
     * system clock — months away from these fixed timestamps. If any layer
     * substituted "now" for the session's own boundaries, 补问成本 would collapse
     * to milliseconds and the {@code eq(REPORTED_AT)} / {@code eq(READY_AT)}
     * verification below would fail. That collapse is exactly what every
     * runnable path reports today, which is why the metric looked healthy while
     * measuring nothing.
     */
    private TroubleshootingIntakeService intakeService() {
        return new TroubleshootingIntakeService(
                sopPersistence, diagnosisService, evidenceRouter, agentTriageService);
    }

    /** Only the return channel matters here; the chain under test ends at dispatch. */
    private static Diagnosis placeholderDiagnosis() {
        return Diagnosis.initial(
                "diag-first-scenario", "case-1", "run-1",
                new IncidentContext(
                        "incident-intake-first-scenario", "CSDP", "csdp-session-service",
                        null, "会话消息发送失败", "P2", "待确认", null, REPORTED_AT, null,
                        "channel:wecom", IncidentCompleteness.STRUCTURED,
                        "会话消息发送失败"),
                RouteMode.LLM_FALLBACK, DiagnosisStatus.NEEDS_INVESTIGATION,
                "证据不足", "待确认", Confidence.LOW, true,
                null, null, null,
                List.of(), List.of(), List.of(),
                null, false, true, List.of());
    }

    private static IntakeMessageEnvelope message(String id, String body, Instant at) {
        return new IntakeMessageEnvelope(
                WORKSPACE_ID, "wecom", id, "wecom:99:group-1", "user-1",
                body, List.of(), at);
    }
}
