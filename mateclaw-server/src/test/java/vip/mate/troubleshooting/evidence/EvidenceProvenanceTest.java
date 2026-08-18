package vip.mate.troubleshooting.evidence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「这批证据是不是夹具」必须从证据自己身上读出来。
 *
 * <p>此前它是一个全局常量：翻一下，每条诊断都改口。这里钉住的是它不再能那样。</p>
 */
class EvidenceProvenanceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T10:00:00Z");

    @Test
    @DisplayName("真源适配器答的 → 不是夹具")
    void evidenceAnsweredByARealAdapterIsNotFixture() {
        assertThat(EvidenceProvenance.fixtureMode(List.of(
                result("prometheus", EvidenceStatus.NORMAL),
                result("elasticsearch", EvidenceStatus.ANOMALY))))
                .isFalse();
    }

    @Test
    @DisplayName("录制回放答的 → 是夹具")
    void recordedReplayIsFixture() {
        assertThat(EvidenceProvenance.fixtureMode(List.of(
                result("recorded-replay:message-send-failed", EvidenceStatus.ANOMALY))))
                .isTrue();
    }

    /**
     * 混了一条夹具进来，整批就不能自称真源——读者不会逐条去分辨，而「部分真实」
     * 最容易被读成「真实」。
     */
    @Test
    @DisplayName("真源里混进一条夹具，整批算夹具")
    void oneFixtureAmongRealEvidenceMakesTheWholeBatchFixture() {
        assertThat(EvidenceProvenance.fixtureMode(List.of(
                result("prometheus", EvidenceStatus.NORMAL),
                result("recorded-replay:db-pool", EvidenceStatus.ANOMALY))))
                .isTrue();
    }

    @Test
    @DisplayName("一条证据都没有时不得自称真源")
    void noEvidenceCannotClaimARealSource() {
        assertThat(EvidenceProvenance.fixtureMode(List.of())).isTrue();
        assertThat(EvidenceProvenance.fixtureMode(null)).isTrue();
    }

    /**
     * MISSING 既不能证明是夹具，也不能证明是真源——它只说明没答上来。
     * 全是 MISSING 时，落回「不得自称真源」。
     */
    @Test
    @DisplayName("没答上来的那条不提供成色信息；全都没答上来则算夹具")
    void unansweredEvidenceCarriesNoProvenanceAndAllUnansweredIsFixture() {
        assertThat(EvidenceProvenance.fixtureMode(List.of(
                result("prometheus", EvidenceStatus.NORMAL),
                result("evidence-spine:unavailable", EvidenceStatus.MISSING))))
                .as("一条没取到不该把另一条真实证据降级")
                .isFalse();

        assertThat(EvidenceProvenance.fixtureMode(List.of(
                result("evidence-spine:unavailable", EvidenceStatus.MISSING))))
                .isTrue();
    }

    /**
     * 方向不对称，默认必须落在保守那一侧：漏登记一个真源，它的证据被标成夹具
     * ——看得见、烦人、但安全；漏登记一个夹具来源，它会**悄悄自称真源**。
     */
    @Test
    @DisplayName("不认识的来源一律按夹具——不认识就没有资格自称真源")
    void anUnrecognisedSourceIsTreatedAsFixture() {
        assertThat(EvidenceProvenance.fixtureMode(List.of(
                result("some-new-thing", EvidenceStatus.ANOMALY))))
                .isTrue();
    }

    /**
     * 与验收那条纪律同源：`source` 是调用方自己写上去的，写成 "guance" 就能让
     * 整条诊断自称真源。只认服务端自己看到的。
     */
    @Test
    @DisplayName("调用方自带的证据不能自证成色——哪怕它把 source 写成真源")
    void callerSuppliedEvidenceCannotDeclareItsOwnProvenance() {
        assertThat(EvidenceProvenance.fixtureMode(
                List.of(result("prometheus", EvidenceStatus.NORMAL)),
                List.of(result("guance:log", EvidenceStatus.ANOMALY))))
                .isTrue();

        assertThat(EvidenceProvenance.fixtureMode(
                List.of(result("prometheus", EvidenceStatus.NORMAL)), List.of()))
                .as("没有自带证据时，服务端自己取的才说了算")
                .isFalse();
    }

    @Test
    @DisplayName("正式 Guance 运行可以诚实记录无数据，但不接受回放或本地降级")
    void formalGuanceRunDistinguishesARealMissingAnswerFromAFallback() {
        assertThat(EvidenceProvenance.fixtureModeForAcceptedGuanceRun(List.of(
                result("guance:dql", EvidenceStatus.MISSING))))
                .as("owner-accepted Guance 返回无记录仍是一次真实观测")
                .isFalse();

        assertThat(EvidenceProvenance.fixtureModeForAcceptedGuanceRun(List.of(
                result("evidence-spine:unavailable", EvidenceStatus.MISSING),
                result("recorded-replay", EvidenceStatus.ANOMALY))))
                .isTrue();
        assertThat(EvidenceProvenance.fixtureModeForAcceptedGuanceRun(List.of()))
                .isTrue();
    }

    /**
     * 整个代码库里杠杆最高的一份清单，现在在这里。
     *
     * <p><b>它从哪儿搬来的。</b> 此前是 {@code TroubleshootingSafetyPolicy
     * .EVIDENCE_IS_FIXTURE}——一个编译期常量，翻它只要一个字符，而每一条诊断都会
     * 立刻改口。那个常量已经删掉：成色现在从每一批证据自己身上推导。但**杠杆没有
     * 消失，只是搬了家**：往这份白名单里加一个名字，就等于宣布那个源取回来的东西
     * 是真的。守卫必须跟着杠杆走，否则就是一道指着错误对象的闸门。</p>
     *
     * <p><b>这条测试不是为了让名单永远不变。</b> 接新真源适配器时本来就该加。它是
     * 为了让加名字成为一次**必须动手改测试**的动作，从而逼人先读下面这份前置。</p>
     *
     * <p>把一个源写进这份名单之前必须先成立的事：
     * <ol>
     *   <li>T7：Workspace owner 已对**当前** binding 指纹提交 {@code ACCEPTED}
     *       （指纹变过就是 {@code STALE}，不算）。先跑
     *       {@code scripts/troubleshooting-t7-preflight.sh} 确认。</li>
     *   <li>它真的被路由到了，并且核心 signal 能取到合法结果——「适配器装上了」
     *       不等于「取得到」。</li>
     *   <li>知道这件事**不会**让手写 Playbook 变得可信：那些阈值仍然没有被任何真实
     *       历史故障标定过。provenance 里的「真实数据校准」一条正是为此而设，它不看
     *       fixtureMode，加完名字之后仍然会挂在那些诊断上。</li>
     * </ol>
     *
     * <p>第 3 条最容易被忽略：这里改的是**证据**的成色，不是**知识**的成色。两者
     * 此前被压在同一个布尔值上，读起来像同一件事。</p>
     */
    @Test
    @DisplayName("宣称某个源是真的，是一次需要前置条件的决定，不是一次顺手的编辑")
    void wideningTheRealSourceListMustRemainADeliberateAct() {
        assertThat(List.of("guance", "prometheus", "elasticsearch"))
                .as("要往真源名单里加一个名字，请先读这条测试 Javadoc 里的三条前置；"
                        + "改这条断言的同时你会读到那份清单——那正是重点")
                .allSatisfy(source -> assertThat(
                        EvidenceProvenance.fixtureMode(
                                List.of(result(source, EvidenceStatus.NORMAL))))
                        .as("%s 被当作真源", source)
                        .isFalse());

        assertThat(EvidenceProvenance.fixtureMode(List.of(
                result("recorded-replay", EvidenceStatus.NORMAL))))
                .as("录制回放不在名单里，也必须一直不在——A10：回放通过不等于已验证")
                .isTrue();
    }

    private static EvidenceResult result(String source, EvidenceStatus status) {
        return new EvidenceResult(
                "EV-" + source.hashCode(), "L", "q", status,
                "", Map.of("count", 1), source, NOW);
    }
}
