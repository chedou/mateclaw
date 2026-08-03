package vip.mate.troubleshooting.evidence;

import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * 这批证据到底是不是夹具——从证据自己身上读，而不是问一个全局开关。
 *
 * <p><b>为什么必须改成推导。</b> {@code EVIDENCE_IS_FIXTURE} 是一个编译期常量：
 * 谁把它翻成 false，**每一条**诊断都会立刻自称真源——包括同一时刻仍在走录制
 * 回放的那些。反过来，只要它还是 true，一条真的从 Prometheus 取到数据的诊断
 * 也只能自称夹具。两种错都来自同一件事：**把一条诊断的事实，交给了一个全局
 * 状态去回答。**</p>
 *
 * <p><b>只回答一个问题。</b> 这里判的是「证据来自夹具还是真源」，不判「这个源
 * 有没有被 owner 验收」，也不判「指挥它的知识是不是手写的」。三件事是三条独立
 * 的轴，合并任何两条，读者都会拿其中一条的结论去推另一条——本项目已经在
 * fixtureMode 上吃过一次这个亏。</p>
 */
public final class EvidenceProvenance {

    /**
     * 已知的真实观测源。**只列真源，其余一律按夹具**。
     *
     * <p>方向是刻意的，而且我第一版写反了：漏登记一个真源，后果是它的证据被
     * 保守地标成夹具——看得见、烦人、但安全；漏登记一个夹具来源，后果是它
     * <b>悄悄自称真源</b>。两种疏忽的代价不对称，所以默认必须落在保守那一侧。
     * 新接真源适配器时在这里加一行，是一件会被测试提醒的事。</p>
     */
    private static final List<String> REAL_SOURCE_PREFIXES = List.of(
            "guance", "prometheus", "elasticsearch");

    private EvidenceProvenance() {
    }

    /**
     * @param collected 服务端**自己**通过适配器取回来的证据
     * @param supplied  调用方随请求自带的证据
     * @return true 表示这条诊断的结论不能声称来自真实观测
     */
    public static boolean fixtureMode(
            Collection<EvidenceResult> collected,
            Collection<EvidenceResult> supplied) {
        // 调用方自带的证据不能自证成色：`source` 是它自己写上去的，写成 "guance"
        // 就能让整条诊断自称真源。这与验收那条纪律是同一条——提交方声称的事实
        // 一律不接受，只认服务端自己看到的。
        if (supplied != null && !supplied.isEmpty()) {
            return true;
        }
        return fixtureMode(collected);
    }

    /**
     * @param evidence 服务端自己取回来的证据
     * @return true 表示这条诊断的结论不能声称来自真实观测
     */
    public static boolean fixtureMode(Collection<EvidenceResult> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            // 一条证据都没有时不能自称真源。「什么都没取到」和「取到的都是真的」
            // 在下游读起来一样，而它们的可信度差着量级。
            return true;
        }
        boolean sawRealAnswer = false;
        for (EvidenceResult result : evidence) {
            if (result.status() == EvidenceStatus.MISSING) {
                // 没答上来的那条不提供任何成色信息——既不能证明是夹具，
                // 也不能证明是真源。
                continue;
            }
            if (!isRealSource(result.source())) {
                // 混了一条夹具进来，整批就不能自称真源：读者不会逐条去分辨，
                // 而"部分真实"最容易被读成"真实"。
                return true;
            }
            sawRealAnswer = true;
        }
        return !sawRealAnswer;
    }

    private static boolean isRealSource(String source) {
        if (source == null || source.isBlank()) {
            // 连问了谁都没记下来，就没有资格声称真源。
            return false;
        }
        String normalized = source.trim().toLowerCase(Locale.ROOT);
        return REAL_SOURCE_PREFIXES.stream().anyMatch(normalized::startsWith);
    }
}
