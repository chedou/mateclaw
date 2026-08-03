package vip.mate.troubleshooting.evidence;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 一次 owner 对某个证据源绑定的验收。平台无关，且不含任何凭据。
 *
 * <p><b>为什么泛化而不是每个适配器抄一份。</b> Guance 的 V184 把这套规则实现过
 * 一次：钉在指纹上、配置一变自动失效、只有 owner 能提交、服务端提交前必须自己
 * 再跑一次。第二个适配器再抄一遍，就会有两套「差不多」的规则，而它们只会越差
 * 越多——A9 说一种能力只有一个实现。</p>
 *
 * <p><b>两半都必须有，缺一不可。</b> {@code checklist} 是人逐项确认的，
 * {@code observed} 是服务端自己重跑一次只读链路看到的。只有前者，验收就是一句
 * 声明；只有后者，验收就成了「能连上」——而能连上不等于查的是对的东西。</p>
 */
public record EvidenceSourceAcceptance(
        String acceptanceId,
        String platform,
        String bindingFingerprint,
        Checklist checklist,
        ObservedFacts observed,
        String acceptedBy,
        Instant acceptedAt) {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{2,127}");
    private static final Pattern SHA256 = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern PLATFORM = Pattern.compile("[a-z][a-z0-9-]{1,63}");

    public EvidenceSourceAcceptance {
        acceptanceId = required(acceptanceId, "acceptanceId");
        platform = required(platform, "platform");
        bindingFingerprint = required(bindingFingerprint, "bindingFingerprint");
        acceptedBy = required(acceptedBy, "acceptedBy");
        if (!ID.matcher(acceptanceId).matches()) {
            throw new IllegalArgumentException("acceptanceId is invalid");
        }
        if (!PLATFORM.matcher(platform).matches()) {
            throw new IllegalArgumentException("platform is invalid");
        }
        if (!SHA256.matcher(bindingFingerprint).matches()) {
            throw new IllegalArgumentException("bindingFingerprint must be SHA-256 hex");
        }
        if (checklist == null || !checklist.complete()) {
            // 半份清单不是验收。允许它，等于允许「先签了再说，剩下的回头补」。
            throw new IllegalArgumentException(
                    "every checklist item must be affirmed by the owner");
        }
        if (observed == null || !observed.usable()) {
            throw new IllegalArgumentException(
                    "acceptance requires facts the server itself re-observed");
        }
        if (acceptedAt == null) {
            throw new IllegalArgumentException("acceptedAt is required");
        }
    }

    /**
     * owner 逐项确认的那几件事。全部为 true 才构成一次验收。
     *
     * @param queryTargetsVerified   查的是不是该查的对象（measurement / index / PromQL）
     * @param fieldMappingVerified   返回字段与 canonical 字段是不是对得上、类型对不对
     * @param timeWindowVerified     时间窗与时间单位是不是对的
     * @param latencyReviewed        查询时延是否可接受，会不会拖垮在线取证
     * @param scopeIsolationVerified 这个绑定只覆盖它该覆盖的 workspace/系统，没有越界
     */
    public record Checklist(
            boolean queryTargetsVerified,
            boolean fieldMappingVerified,
            boolean timeWindowVerified,
            boolean latencyReviewed,
            boolean scopeIsolationVerified) {

        boolean complete() {
            return queryTargetsVerified && fieldMappingVerified && timeWindowVerified
                    && latencyReviewed && scopeIsolationVerified;
        }
    }

    /**
     * 服务端在记录验收之前**自己**重跑一次只读取证看到的事实。
     *
     * <p>只保留结构化计数与耗时：原始日志、查询文本、凭据都不进这里。请求里也
     * 不接受这些字段——全部由服务端重算，否则提交方就能声称一次从没发生的验证。</p>
     */
    public record ObservedFacts(
            String signalKind,
            int canonicalFieldsObserved,
            long durationMs) {

        boolean usable() {
            return signalKind != null && !signalKind.isBlank()
                    && canonicalFieldsObserved > 0 && durationMs >= 0;
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
