package vip.mate.troubleshooting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 整个代码库里杠杆最高的一个开关。
 *
 * <p>{@code EVIDENCE_IS_FIXTURE} 决定每一条诊断对外宣称自己的数字是不是真的。
 * 它是一个编译期常量，改它只要一个字符，而在这条测试之前**没有任何东西钉住它**
 * ——翻了没人会知道。</p>
 *
 * <p><b>这条测试不是为了让它永远是 true。</b> 它是为了让翻转成为一次**必须动手
 * 改测试**的动作，从而逼人先读下面这份前置清单。一个一字之差就能悄悄发生的
 * 改动，和一个必须删掉断言、连带删掉清单的改动，是两回事。</p>
 */
class TroubleshootingSafetyPolicyTest {

    /**
     * 翻转 {@code EVIDENCE_IS_FIXTURE} 之前必须先成立的事：
     *
     * <ol>
     *   <li>T7：Workspace owner 已对**当前** binding 指纹提交 {@code ACCEPTED}
     *       （指纹变过就是 {@code STALE}，不算）。先跑
     *       {@code scripts/troubleshooting-t7-preflight.sh} 确认。</li>
     *   <li>三个核心 signal（log_search / log_trace_bundle / contrast_sample）
     *       都真的路由到了真源并可用。</li>
     *   <li>知道翻转**不会**让手写 Playbook 变得可信：那些阈值仍然没有被任何
     *       真实历史故障标定过。provenance 里的「真实数据校准」一条正是为此而设，
     *       它不看 fixtureMode，翻转之后仍然会挂在那些诊断上。</li>
     * </ol>
     *
     * <p>第 3 条是最容易被忽略的：翻转改变的是**证据**的成色，不是**知识**的成色。
     * 两者此前被压在同一个布尔值上，读起来像同一件事。</p>
     */
    @Test
    @DisplayName("宣称证据为真是一次需要前置条件的决定，不是一次一字之差的编辑")
    void claimingEvidenceIsRealMustRemainADeliberateAct() {
        assertThat(TroubleshootingSafetyPolicy.EVIDENCE_IS_FIXTURE)
                .as("要把它改成 false，请先读这条测试的 Javadoc 里的三条前置；"
                        + "删掉这条断言的同时，你也删掉了那份清单——那正是重点")
                .isTrue();
    }
}
