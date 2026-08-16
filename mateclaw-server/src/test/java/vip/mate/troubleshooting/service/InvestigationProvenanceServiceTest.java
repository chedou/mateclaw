package vip.mate.troubleshooting.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.Confidence;
import vip.mate.troubleshooting.model.ConclusionType;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.DiagnosisStatus;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.InvestigationProvenance;
import vip.mate.troubleshooting.model.PlaybookVersionRef;
import vip.mate.troubleshooting.model.RouteMode;
import vip.mate.troubleshooting.model.SopEntry;
import vip.mate.troubleshooting.synthesis.ApprovedPlaybookVersion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「这次调查动用了什么、没动用什么」。
 *
 * <p>The participant half is easy and mostly mechanical. The tests that matter
 * are about the other half: this product's safety argument is a set of things
 * that did not happen, and a view that lists only participants lets the reader
 * assume the rest — more generously than the truth.</p>
 */
class InvestigationProvenanceServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-02T10:00:00Z");

    private final TroubleshootingPersistenceService persistence =
            mock(TroubleshootingPersistenceService.class);
    private final TroubleshootingPlaybookVersionService versions =
            mock(TroubleshootingPlaybookVersionService.class);
    private final InvestigationProvenanceService service =
            new InvestigationProvenanceService(persistence, versions);

    /**
     * The negatives are the reason this exists. Each is a mechanism a reviewer
     * can go and check — "没有执行器" is falsifiable, "很安全" is not.
     */
    @Test
    @DisplayName("确定性路径必须明说：零模型、不经过 skills、无生产写执行器、只读")
    void aDeterministicInvestigationStatesWhatDidNotParticipate() {
        stored(deterministic(true, false));
        frozen();

        InvestigationProvenance provenance = service.explain(7L, "diag-1");

        assertThat(provenance.abstentions())
                .extracting(InvestigationProvenance.Abstention::capability)
                .contains("大模型", "平台通用 Skills / Tools 注册表", "生产写执行器", "写操作");
        assertThat(provenance.reasoning().modelInvoked()).isFalse();
        assertThat(provenance.reasoning().modelIdentity())
                .as("没有模型参与时不得留下一个像是型号的字符串")
                .isNull();
    }

    @Test
    @DisplayName("fixture 证据必须自己说出来，不能等人去别处发现")
    void fixtureEvidenceAnnouncesItself() {
        stored(deterministic(true, false));
        frozen();

        assertThat(service.explain(7L, "diag-1").abstentions())
                .extracting(InvestigationProvenance.Abstention::capability)
                .contains("真实观测源");
        assertThat(service.explain(7L, "diag-1").abstentions())
                .anyMatch(item -> item.reason().contains("A10"));
    }

    /**
     * 手写夹具与真实归纳在注册表里长得一样。在用它下结论的地方，这个区别最要紧
     * ——T0.9 问的正是这件事。
     */
    @Test
    @DisplayName("说清这份知识哪来的：手写夹具还是真实归纳")
    void itNamesWhereTheKnowledgeCameFrom() {
        stored(deterministic(true, false));
        frozen();

        InvestigationProvenance.Knowledge knowledge = service.explain(7L, "diag-1").knowledge();
        assertThat(knowledge.origin()).isEqualTo("MANUAL_WRITE");
        assertThat(knowledge.playbookVersion()).isEqualTo(1);
        assertThat(knowledge.readable()).isTrue();
    }

    @Test
    @DisplayName("冻结版本读不到时如实说，不拿当前版本冒充当时那一份")
    void anUnreadableFrozenVersionIsReportedRatherThanSubstituted() {
        stored(deterministic(true, false));
        when(versions.findByRef(eq(7L), any(PlaybookVersionRef.class)))
                .thenReturn(Optional.empty());

        InvestigationProvenance.Knowledge knowledge = service.explain(7L, "diag-1").knowledge();
        assertThat(knowledge.readable()).isFalse();
        assertThat(knowledge.note()).contains("不拿当前版本冒充");
        assertThat(knowledge.origin())
                .as("读不到就不知道来源；这里编一个来源比留空更糟")
                .isNull();
    }

    /**
     * "我们查过" 与 "我们查到了" 必须分开。一条 MISSING 也要出现在清单里——
     * 把它省掉，读者会以为那一步根本没发生。
     */
    @Test
    @DisplayName("没取到的证据照样列出来，并且不算已回答、不算被引用")
    void anUnansweredCollectorStaysOnTheListAndIsNotCountedAsAnAnswer() {
        stored(deterministic(true, false));
        frozen();

        List<InvestigationProvenance.Collector> collectors =
                service.explain(7L, "diag-1").collectors();

        assertThat(collectors).extracting(InvestigationProvenance.Collector::requestId)
                .containsExactly("EV-OK", "EV-GONE");
        InvestigationProvenance.Collector gone = collectors.get(1);
        assertThat(gone.answered()).isFalse();
        assertThat(gone.adapter())
                .as("连问了谁都要记下来；空来源本身就是一个发现")
                .isNotBlank();
        assertThat(collectors.getFirst().answered()).isTrue();
    }

    /**
     * The test that changed the contract. Citations are required of the model
     * path, not the error-code path, so a plain {@code false} there would render
     * as 「这条证据没有支撑结论」 — a different and much worse claim than
     * 「本路径不维护引用清单」. Null keeps the two apart.
     */
    @Test
    @DisplayName("不维护引用清单的路径报 null，不报 false")
    void aPathThatKeepsNoCitationListReportsNullRatherThanFalse() {
        stored(deterministic(true, false));
        frozen();

        assertThat(service.explain(7L, "diag-1").collectors())
                .extracting(InvestigationProvenance.Collector::cited)
                .as("错误码路径不填引用清单；false 会被读成「没有证据支撑」")
                .containsOnlyNulls();
    }

    @Test
    @DisplayName("契约拒绝「没取到却被引用」")
    void evidenceThatNeverAnsweredCannotBeCited() {
        assertThatThrownBy(() -> new InvestigationProvenance.Collector(
                "EV-GONE", "metric", "guance", EvidenceStatus.MISSING,
                false, true, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A1");
    }

    /**
     * 投入使用前最要紧的一格。
     *
     * <p>{@code fixtureMode} 现在从每批证据自己身上推导——全局常量
     * {@code EVIDENCE_IS_FIXTURE} 已经删掉。危险没有消失，只是换了形状：T7 真源
     * 接通那天它会**自动**变 false，每一条诊断都会变成「真源」，包括那些由手写
     * Playbook 路由、阈值从没用真实历史故障标定过的。
     * 证据是真的 ≠ 知识是真的：两个独立的轴，此前被压成一个布尔值。
     *
     * <p>所以这条断言用 {@code fixtureMode=false} 构造，也就是**接通真源之后的世界**：
     * 手写知识的警告必须仍然在。它一旦消失，这个产品就会拿着没人校准过的阈值
     * 对真实故障下结论，而页面上什么都不说。</p>
     */
    @Test
    @DisplayName("接上真源之后，手写知识的警告必须仍然在")
    void connectingARealSourceDoesNotMakeHandWrittenThresholdsTrustworthy() {
        stored(deterministic(false, false));
        frozen();

        List<InvestigationProvenance.Abstention> abstentions =
                service.explain(7L, "diag-1").abstentions();

        assertThat(abstentions)
                .extracting(InvestigationProvenance.Abstention::capability)
                .as("证据变真了，知识没有；这一条不看 fixtureMode")
                .contains("真实数据校准")
                .doesNotContain("真实观测源");
        assertThat(abstentions)
                .anyMatch(item -> item.reason().contains("接上真源不会让手写阈值变得可信"));
    }

    @Test
    @DisplayName("归纳产出的知识不挂「未校准」的牌子")
    void inducedKnowledgeIsNotFlaggedAsHandWritten() {
        stored(deterministic(false, false));
        when(versions.findByRef(eq(7L), any(PlaybookVersionRef.class)))
                .thenReturn(Optional.of(new ApprovedPlaybookVersion(
                        "playbook-1", 1, "csdp:903001", "APPROVED",
                        "EVIDENCE_DERIVED", "record-1", null, null,
                        "reviewer", "归纳后评审通过", null, null, null, null,
                        playbook(), NOW.minusSeconds(600), NOW.minusSeconds(600))));

        assertThat(service.explain(7L, "diag-1").abstentions())
                .extracting(InvestigationProvenance.Abstention::capability)
                .doesNotContain("真实数据校准");
    }

    @Test
    @DisplayName("读不到冻结版本时，不许默认它是已校准的知识")
    void unreadableKnowledgeIsNotAssumedCalibrated() {
        stored(deterministic(false, false));
        when(versions.findByRef(eq(7L), any(PlaybookVersionRef.class)))
                .thenReturn(Optional.empty());

        assertThat(service.explain(7L, "diag-1").abstentions())
                .extracting(InvestigationProvenance.Abstention::capability)
                .as("判不出来就说判不出来，沉默会被读成「已校准」")
                .contains("知识来源判定");
    }

    @Test
    @DisplayName("演练必须标出来，否则会被当成一次真实处置")
    void aRehearsalSaysSo() {
        stored(deterministic(true, true));
        frozen();

        assertThat(service.explain(7L, "diag-1").abstentions())
                .extracting(InvestigationProvenance.Abstention::capability)
                .contains("正式流程");
    }

    @Test
    @DisplayName("契约本身拒绝一份只列参与者的 provenance")
    void aParticipantOnlyProvenanceIsRejectedByTheContract() {
        assertThatThrownBy(() -> new InvestigationProvenance(
                "diag-1", null, List.of(),
                new InvestigationProvenance.Reasoning(
                        RouteMode.DETERMINISTIC,
                        vip.mate.troubleshooting.model.InvestigationMode.ERROR_CODE_PLAYBOOK,
                        vip.mate.troubleshooting.model.RouteAuthority.EXPLICIT,
                        ConclusionType.LOCATED, false, null, 1, true),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("did not participate");
    }

    @Test
    @DisplayName("模型跑了就必须点名，没跑就不许留名")
    void aModelThatRanMustBeNamedAndOneThatDidNotMustNot() {
        assertThatThrownBy(() -> new InvestigationProvenance.Reasoning(
                RouteMode.LLM_FALLBACK,
                vip.mate.troubleshooting.model.InvestigationMode.OPEN_DISCOVERY,
                vip.mate.troubleshooting.model.RouteAuthority.MODEL_PROPOSED,
                ConclusionType.INSUFFICIENT_EVIDENCE, true, null, 0, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InvestigationProvenance.Reasoning(
                RouteMode.DETERMINISTIC,
                vip.mate.troubleshooting.model.InvestigationMode.ERROR_CODE_PLAYBOOK,
                vip.mate.troubleshooting.model.RouteAuthority.EXPLICIT,
                ConclusionType.LOCATED, false, "some-model", 0, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void stored(Diagnosis diagnosis) {
        when(persistence.get(7L, "diag-1"))
                .thenReturn(new StoredDiagnosis(diagnosis, 1, false));
    }

    private void frozen() {
        when(versions.findByRef(eq(7L), any(PlaybookVersionRef.class)))
                .thenReturn(Optional.of(new ApprovedPlaybookVersion(
                        "playbook-1", 1, "csdp:903001", "APPROVED",
                        "MANUAL_WRITE", "record-1", null, null,
                        "reviewer", "夹具种子", null, null, null, null,
                        playbook(), NOW.minusSeconds(600), NOW.minusSeconds(600))));
    }

    private static SopEntry playbook() {
        return new SopEntry(
                "playbook-1", SopEntry.CURRENT_CONTRACT_VERSION,
                "CSDP", "903001", "csdp-wechat",
                "数据库访问异常排查", "连接池被慢查询占满", "database", "数据库平台组",
                "approved", true,
                List.of(), List.of(), List.of(), List.of());
    }

    private Diagnosis deterministic(boolean fixtureMode, boolean rehearsal) {
        return Diagnosis.initial(
                "diag-1", "case-1", "run-1",
                new IncidentContext(
                        "inc-1", "CSDP", "csdp-wechat", "903001",
                        "数据库访问异常", "P0", "所有客户", null,
                        NOW.minusSeconds(300), null, "alert_fixture",
                        IncidentCompleteness.STRUCTURED, "error_code=903001"),
                RouteMode.DETERMINISTIC,
                DiagnosisStatus.READY_FOR_HUMAN,
                "连接池被慢查询占满", "慢查询", Confidence.HIGH, false,
                "csdp:903001", "数据库访问异常排查",
                new PlaybookVersionRef("playbook-1", 1),
                List.of(
                        new EvidenceResult("EV-OK", "log_search", "q",
                                EvidenceStatus.ANOMALY, "命中", Map.of("match_count", 5),
                                "guance", NOW),
                        new EvidenceResult("EV-GONE", "metric", "q",
                                EvidenceStatus.MISSING, "取证失败", Map.of(),
                                "evidence-spine:unavailable", NOW)),
                List.of("pool_saturated"),
                List.of(), null, rehearsal, fixtureMode, List.of());
    }
}
