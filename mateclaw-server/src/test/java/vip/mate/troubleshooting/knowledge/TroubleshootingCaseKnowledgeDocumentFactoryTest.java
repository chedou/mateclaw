package vip.mate.troubleshooting.knowledge;

import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.model.ClosureRecord;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.InvestigationMode;
import vip.mate.troubleshooting.model.NorthStarTimings;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.RouteAuthority;
import vip.mate.troubleshooting.model.RouteMode;
import vip.mate.troubleshooting.model.TimelineEvent;
import vip.mate.troubleshooting.service.StoredDiagnosis;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TroubleshootingCaseKnowledgeDocumentFactoryTest {

    private static final Instant NOW = Instant.parse("2026-08-03T02:00:00Z");
    private final TroubleshootingCaseKnowledgeDocumentFactory factory =
            new TroubleshootingCaseKnowledgeDocumentFactory();

    @Test
    void openCaseIsSearchableButCannotMasqueradeAsAReusableConclusion() {
        Diagnosis diagnosis = waitingDiagnosis(
                "Authorization: Bearer eyJabc.def.ghi should disappear");

        TroubleshootingCaseKnowledgeDocument document = factory.create(
                new StoredDiagnosis(diagnosis, 4, false));

        assertThat(document.authoritativeResolution()).isFalse();
        assertThat(document.slug()).isEqualTo(
                "troubleshooting-case-diag-message-send-v4");
        assertThat(document.markdown())
                .contains("调查记录（不作为根因依据）")
                .contains("未形成可复用结论")
                .contains("query-failed-message")
                .contains("<REDACTED>")
                .doesNotContain("eyJabc.def.ghi")
                .doesNotContain("L::secret_query")
                .doesNotContain("raw-line-that-must-not-be-exported");
    }

    @Test
    void recoveredClosedCaseCarriesTheVerifiedResolutionAndTraceability() {
        Diagnosis confirmed = waitingDiagnosis("会话消息发送失败", false, false)
                .confirmed(List.of(new TimelineEvent(
                        NOW.plusSeconds(9), "人工确认结论", "alice", "done")));
        ClosureRecord closure = new ClosureRecord(
                ClosureOutcome.RECOVERED,
                "修复会话服务的下游连接后，回归验证通过",
                true,
                null,
                null,
                "alice",
                NOW.plusSeconds(60));
        Diagnosis closed = confirmed.closed(
                closure,
                List.of(),
                List.of(
                        new TimelineEvent(NOW.plusSeconds(9), "人工确认结论", "alice", "done"),
                        new TimelineEvent(NOW.plusSeconds(60), "关闭排障单", "alice", "done")));

        TroubleshootingCaseKnowledgeDocument document = factory.create(
                new StoredDiagnosis(closed, 6, false));

        assertThat(document.authoritativeResolution()).isTrue();
        assertThat(document.markdown())
                .contains("已验证解决案例")
                .contains("会话服务调用下游时连接被拒绝")
                .contains("修复会话服务的下游连接后，回归验证通过")
                .contains("diag-message-send")
                .contains("case-message-send")
                .contains("playbook-message-send@2");
    }

    @Test
    void recoveredReplayRemainsARecordRatherThanBecomingAuthoritativeKnowledge() {
        Diagnosis confirmed = waitingDiagnosis("回放中的会话消息发送失败")
                .confirmed(List.of(new TimelineEvent(
                        NOW.plusSeconds(9), "人工确认结论", "alice", "done")));
        Diagnosis closed = confirmed.closed(
                new ClosureRecord(
                        ClosureOutcome.RECOVERED,
                        "回放验证通过",
                        true,
                        null,
                        null,
                        "alice",
                NOW.plusSeconds(60)),
                List.of(),
                List.of(
                        new TimelineEvent(NOW.plusSeconds(9), "人工确认结论", "alice", "done"),
                        new TimelineEvent(NOW.plusSeconds(60), "关闭排障单", "alice", "done")));

        TroubleshootingCaseKnowledgeDocument document = factory.create(
                new StoredDiagnosis(closed, 6, false));

        assertThat(document.authoritativeResolution()).isFalse();
        assertThat(document.markdown())
                .contains("调查记录（不作为根因依据）")
                .contains("未形成可复用结论")
                .doesNotContain("已验证根因：");
    }

    private Diagnosis waitingDiagnosis(String title) {
        return waitingDiagnosis(title, true, true);
    }

    private Diagnosis waitingDiagnosis(String title, boolean rehearsal, boolean fixtureMode) {
        IncidentContext incident = new IncidentContext(
                "inc-message-send",
                "CSDP",
                "csdp-session-service",
                null,
                title,
                "P2",
                "部分用户会话消息无法发送",
                "ps-safe-1",
                NOW,
                null,
                "web:scenario",
                IncidentCompleteness.SYMPTOM,
                title);
        EvidenceResult evidence = new EvidenceResult(
                "query-failed-message",
                "L",
                "L::secret_query",
                EvidenceStatus.ANOMALY,
                "失败消息样本已找到",
                Map.of("raw", "raw-line-that-must-not-be-exported"),
                "recorded-replay",
                NOW.plusSeconds(5));
        return Diagnosis.initial(
                "diag-message-send",
                "case-message-send",
                "run-message-send",
                incident,
                RouteMode.DETERMINISTIC,
                InvestigationMode.SCENARIO_PLAYBOOK,
                RouteAuthority.EXPLICIT,
                ConclusionType.LOCATED,
                NorthStarTimings.concluded(NOW, NOW.plusSeconds(2), NOW.plusSeconds(8)),
                DiagnosisStatus.READY_FOR_HUMAN,
                "已完成三次只读取证",
                "会话服务调用下游时连接被拒绝",
                Confidence.HIGH,
                false,
                "csdp:scenario:message_send_failed",
                "会话消息发送失败排查指南",
                "会话平台组",
                new PlaybookVersionRef("playbook-message-send", 2),
                List.of(evidence),
                List.of("失败样本与成功样本存在差异"),
                List.of(),
                "会话平台组",
                rehearsal,
                fixtureMode,
                List.of(),
                List.of());
    }
}
