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

    private static EvidenceResult result(String source, EvidenceStatus status) {
        return new EvidenceResult(
                "EV-" + source.hashCode(), "L", "q", status,
                "", Map.of("count", 1), source, NOW);
    }
}
