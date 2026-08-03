package vip.mate.troubleshooting.evidence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.EvidenceRequest;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.EvidenceStatus;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.model.TroubleshootingSourceAcceptanceEntity;
import vip.mate.troubleshooting.repository.TroubleshootingSourceAcceptanceMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 平台无关的 owner 验收：一个实现服务所有证据源适配器。
 *
 * <p><b>提交方声称的任何事实一律不接受。</b> 请求里只有平台和 owner 逐项确认的
 * 清单；指纹由适配器算、验证事实由服务端**自己重跑一次只读取证**得到、actor 取自
 * 鉴权上下文。一旦允许提交方自带指纹或验证结果，验收就退化成一句可以随手写下的
 * 声明，而这张表存在的全部意义就是它不是。</p>
 *
 * <p><b>失效不需要有人记得去做。</b> 验收钉在绑定指纹上，配置一改指纹就变，旧行
 * 自然对不上，状态读出来就是 {@code STALE}。没有「记得作废」这一步，也就没有忘记
 * 作废这种可能。</p>
 */
@Service
public class EvidenceSourceAcceptanceService {

    /** 验证用的探针上下文：只用于让服务端自己看一眼，不产生任何 Diagnosis。 */
    private static final IncidentContext PROBE_CONTEXT = new IncidentContext(
            "acceptance-probe", "ACCEPTANCE", "acceptance-probe", null,
            "证据源验收探针", "P3", "无", null,
            Instant.EPOCH, null, "acceptance", IncidentCompleteness.SYMPTOM,
            "owner acceptance read-only probe");

    private final List<EvidenceSourceAdapter> adapters;
    private final TroubleshootingSourceAcceptanceMapper mapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public EvidenceSourceAcceptanceService(
            List<EvidenceSourceAdapter> adapters,
            TroubleshootingSourceAcceptanceMapper mapper,
            ObjectMapper objectMapper) {
        this(adapters, mapper, objectMapper, Clock.systemUTC());
    }

    EvidenceSourceAcceptanceService(
            List<EvidenceSourceAdapter> adapters,
            TroubleshootingSourceAcceptanceMapper mapper,
            ObjectMapper objectMapper,
            Clock clock) {
        this.adapters = List.copyOf(adapters == null ? List.of() : adapters);
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public EvidenceSourceAcceptanceView inspect(long workspaceId, String platform) {
        String safePlatform = requirePlatform(platform);
        FingerprintedAdapter adapter = resolve(safePlatform);
        if (adapter == null) {
            return blocked(safePlatform, "no evidence source adapter is registered for "
                    + safePlatform);
        }
        String fingerprint = adapter.fingerprint();
        if (fingerprint == null) {
            return blocked(safePlatform,
                    "the binding is absent or incomplete, so there is nothing to accept");
        }
        Optional<EvidenceSourceAcceptance> stored = read(workspaceId, safePlatform, fingerprint);
        if (stored.isPresent()) {
            return new EvidenceSourceAcceptanceView(
                    EvidenceSourceAcceptanceView.Status.ACCEPTED,
                    safePlatform, fingerprint, stored.get(), List.of());
        }
        boolean acceptedSomethingElse = countFor(workspaceId, safePlatform) > 0;
        List<String> blockers = new ArrayList<>();
        if (acceptedSomethingElse) {
            blockers.add("配置在上次验收之后变过；那次验收针对的是另一份绑定，需重做");
        } else {
            blockers.add("尚无 owner 验收记录");
        }
        return new EvidenceSourceAcceptanceView(
                acceptedSomethingElse
                        ? EvidenceSourceAcceptanceView.Status.STALE
                        : EvidenceSourceAcceptanceView.Status.NOT_ACCEPTED,
                safePlatform, fingerprint, null, blockers);
    }

    /**
     * 记录一次验收，但**只在服务端自己先跑通一次只读取证之后**。
     *
     * @param checklist owner 逐项确认的结果；任何一项为 false 都不构成验收
     * @param actor     取自鉴权上下文的 owner，不接受请求体传入
     */
    @Transactional
    public EvidenceSourceAcceptanceView accept(
            long workspaceId,
            String platform,
            EvidenceSourceAcceptance.Checklist checklist,
            String actor) {
        if (workspaceId <= 0) {
            throw invalid("workspaceId is required");
        }
        String safePlatform = requirePlatform(platform);
        if (actor == null || actor.isBlank()) {
            throw invalid("an authenticated owner is required");
        }
        if (checklist == null || !allAffirmed(checklist)) {
            throw invalid("每一项都必须由 owner 明确确认后才能提交验收");
        }
        FingerprintedAdapter adapter = resolve(safePlatform);
        if (adapter == null || adapter.fingerprint() == null) {
            throw conflict("the binding is absent or incomplete; there is nothing to accept");
        }

        EvidenceSourceAcceptance.ObservedFacts observed = reobserve(workspaceId, adapter);
        EvidenceSourceAcceptance acceptance = new EvidenceSourceAcceptance(
                "src-accept-" + UUID.randomUUID().toString().replace("-", ""),
                safePlatform,
                adapter.fingerprint(),
                checklist,
                observed,
                actor.trim(),
                Instant.now(clock));

        TroubleshootingSourceAcceptanceEntity entity =
                new TroubleshootingSourceAcceptanceEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setAcceptanceId(acceptance.acceptanceId());
        entity.setPlatform(safePlatform);
        entity.setBindingFingerprint(acceptance.bindingFingerprint());
        entity.setAggregateJson(write(acceptance));
        entity.setVersion(0);
        entity.setDeleted(0);
        LocalDateTime now = LocalDateTime.ofInstant(acceptance.acceptedAt(), ZoneOffset.UTC);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        mapper.insert(entity);
        return new EvidenceSourceAcceptanceView(
                EvidenceSourceAcceptanceView.Status.ACCEPTED,
                safePlatform, acceptance.bindingFingerprint(), acceptance, List.of());
    }

    /**
     * 服务端自己跑一次，看它到底能不能取到证据。
     *
     * <p>取不到就直接拒绝验收——「我确认过了」加上「其实取不到」不该产出一条
     * 有效记录，那正是最容易被走过场的组合。</p>
     */
    private EvidenceSourceAcceptance.ObservedFacts reobserve(
            long workspaceId, FingerprintedAdapter adapter) {
        String signalKind = adapter.signalKind();
        if (signalKind == null) {
            throw conflict("this adapter does not declare a verifiable signal kind");
        }
        EvidenceRequest probe = new EvidenceRequest(
                "ACCEPT-PROBE", signalKind, "owner acceptance read-only probe",
                Map.of("search_term", "acceptance_probe"), "-15m", true);
        long startedAt = System.nanoTime();
        EvidenceResult result;
        try {
            result = adapter.adapter().collect(workspaceId, probe, PROBE_CONTEXT);
        } catch (RuntimeException failure) {
            throw conflict("the source could not be re-observed, so acceptance was refused");
        }
        long durationMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        if (result == null || result.status() == EvidenceStatus.MISSING
                || result.observed().isEmpty()) {
            throw conflict("the source returned no usable evidence, so acceptance was refused");
        }
        return new EvidenceSourceAcceptance.ObservedFacts(
                signalKind, result.observed().size(), durationMs);
    }

    private boolean allAffirmed(EvidenceSourceAcceptance.Checklist checklist) {
        return checklist.queryTargetsVerified() && checklist.fieldMappingVerified()
                && checklist.timeWindowVerified() && checklist.latencyReviewed()
                && checklist.scopeIsolationVerified();
    }

    private Optional<EvidenceSourceAcceptance> read(
            long workspaceId, String platform, String fingerprint) {
        TroubleshootingSourceAcceptanceEntity entity = mapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingSourceAcceptanceEntity>()
                        .eq(TroubleshootingSourceAcceptanceEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingSourceAcceptanceEntity::getPlatform, platform)
                        .eq(TroubleshootingSourceAcceptanceEntity::getBindingFingerprint,
                                fingerprint)
                        .eq(TroubleshootingSourceAcceptanceEntity::getDeleted, 0));
        if (entity == null) {
            return Optional.empty();
        }
        try {
            EvidenceSourceAcceptance parsed = objectMapper.readValue(
                    entity.getAggregateJson(), EvidenceSourceAcceptance.class);
            // 存下来的指纹与行上的指纹必须一致，否则这条记录本身不可信。
            return parsed.bindingFingerprint().equals(fingerprint)
                    ? Optional.of(parsed)
                    : Optional.empty();
        } catch (JsonProcessingException unreadable) {
            return Optional.empty();
        }
    }

    private long countFor(long workspaceId, String platform) {
        Long count = mapper.selectCount(
                new LambdaQueryWrapper<TroubleshootingSourceAcceptanceEntity>()
                        .eq(TroubleshootingSourceAcceptanceEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingSourceAcceptanceEntity::getPlatform, platform)
                        .eq(TroubleshootingSourceAcceptanceEntity::getDeleted, 0));
        return count == null ? 0L : count;
    }

    /** Adapters that can be accepted must be able to name the exact binding. */
    private FingerprintedAdapter resolve(String platform) {
        for (EvidenceSourceAdapter adapter : adapters) {
            if (!platform.equals(adapter.platform())) {
                continue;
            }
            if (adapter instanceof PrometheusEvidenceAdapter prometheus) {
                return new FingerprintedAdapter(
                        adapter, prometheus.bindingFingerprint(), "metric");
            }
            if (adapter instanceof ElasticsearchEvidenceAdapter elasticsearch) {
                return new FingerprintedAdapter(
                        adapter, elasticsearch.bindingFingerprint(), "log_search");
            }
            // 没有指纹的适配器无法被验收。这不是遗漏，是拒绝：验收必须钉在一个
            // 会随配置改变的东西上，否则它保证不了任何事。
            return new FingerprintedAdapter(adapter, null, null);
        }
        return null;
    }

    private EvidenceSourceAcceptanceView blocked(String platform, String reason) {
        return new EvidenceSourceAcceptanceView(
                EvidenceSourceAcceptanceView.Status.BLOCKED, platform, null, null,
                List.of(reason));
    }

    private String write(EvidenceSourceAcceptance acceptance) {
        try {
            return objectMapper.writeValueAsString(acceptance);
        } catch (JsonProcessingException failure) {
            throw new MateClawException(
                    "err.troubleshooting.source_acceptance_persistence_failed", 500,
                    "acceptance could not be serialized");
        }
    }

    private String requirePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            throw invalid("platform is required");
        }
        return platform.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private MateClawException invalid(String message) {
        return new MateClawException("err.troubleshooting.invalid_request", 400, message);
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.source_acceptance_conflict", 409, message);
    }

    private record FingerprintedAdapter(
            EvidenceSourceAdapter adapter, String fingerprint, String signalKind) {
    }
}
