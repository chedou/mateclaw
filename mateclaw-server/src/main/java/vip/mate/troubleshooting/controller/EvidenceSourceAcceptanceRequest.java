package vip.mate.troubleshooting.controller;

import jakarta.validation.constraints.AssertTrue;
import vip.mate.troubleshooting.evidence.EvidenceSourceAcceptance;

/**
 * owner 验收请求体：**只有清单，别的什么都没有**。
 *
 * <p>没有指纹、没有验证计数、没有 actor、没有验收状态。每一项都由服务端重算或
 * 从鉴权上下文取——一旦允许提交方自带其中任何一项，这次验收就变成了一句可以
 * 随手写下的声明，而这条链路存在的全部意义就是它不是。</p>
 *
 * <p>五项都必须为 {@code true}：{@code @AssertTrue} 让「先签了再说，剩下的回头补」
 * 在进入服务之前就被拒绝。</p>
 */
public record EvidenceSourceAcceptanceRequest(
        @AssertTrue(message = "必须确认查询目标（measurement / index / PromQL）核对无误")
        boolean queryTargetsVerified,
        @AssertTrue(message = "必须确认返回字段与 canonical 字段及类型对得上")
        boolean fieldMappingVerified,
        @AssertTrue(message = "必须确认时间窗与时间单位正确")
        boolean timeWindowVerified,
        @AssertTrue(message = "必须确认查询时延不会拖垮在线取证")
        boolean latencyReviewed,
        @AssertTrue(message = "必须确认该绑定没有越出它该覆盖的范围")
        boolean scopeIsolationVerified) {

    public EvidenceSourceAcceptance.Checklist toChecklist() {
        return new EvidenceSourceAcceptance.Checklist(
                queryTargetsVerified, fieldMappingVerified, timeWindowVerified,
                latencyReviewed, scopeIsolationVerified);
    }
}
