package vip.mate.troubleshooting.evidence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.TroubleshootingEvidenceRouteEntity;
import vip.mate.troubleshooting.repository.TroubleshootingEvidenceRouteMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 让一个 workspace 自己声明「这个系统的这类信号去问哪个源」。
 *
 * <p><b>为什么它必须存在。</b> 在此之前路由只有一份，在 {@code application.yml}
 * 里。新租户能注册 Playbook、能批准、能开案、能跑取证计划，然后每一条证据都回
 * {@code MISSING/router:unconfigured}——**接一个系统要改发布物里的文件并重新发版**。
 * 这和随包回放目录是同一种病：本该属于租户的事实被焊死在构建产物里。</p>
 *
 * <p><b>它放宽了什么，没放宽什么。</b> 一条路由只能说「按什么顺序问哪几个平台」。
 * 端点与凭据仍然只在运维配置的适配器里，所以租户是在**已启用的源之间做选择**，
 * 不可能引入一个新的源。而且这条路加了 workspace 维度，是在收窄：YAML 那张表只
 * 按 system 名字索引，任何 workspace 只要把系统命名成 {@code CSDP}，就继承了
 * CSDP 的路由、打到 CSDP 的观测端点上。</p>
 *
 * <p><b>刻意不做的事。</b> 这里不判「这个源有没有被 owner 验收」——那是
 * {@link EvidenceSourceAcceptanceService} 那条轴。三条轴（证据成色 / 源是否验收 /
 * 知识成色）合并任何两条，读者都会拿其中一条的结论去推另一条。</p>
 */
@Service
public class EvidenceRouteService implements WorkspaceEvidenceRoutes {

    /** 一格路由最多几个平台。回落链再长也没有意义，只会让失败更难解释。 */
    private static final int MAX_PLATFORMS = 8;
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final int MAX_REASON = 500;

    private final TroubleshootingEvidenceRouteMapper mapper;
    /**
     * 直接持有适配器，而不是持有 {@link EvidenceSourceRouter}。
     *
     * <p>一半是因为 router 反过来要读本服务（那是个 Spring 循环依赖），一半是因为
     * 本服务真正要问的只有「有哪些平台、它们此刻好不好、支不支持这条 signal」——
     * 这三件事都属于适配器，跟路由无关。</p>
     */
    private final List<EvidenceSourceAdapter> adapters;

    public EvidenceRouteService(
            TroubleshootingEvidenceRouteMapper mapper,
            List<EvidenceSourceAdapter> adapters) {
        this.mapper = mapper;
        this.adapters = List.copyOf(adapters == null ? List.of() : adapters);
    }

    @Override
    public Optional<List<String>> find(
            long workspaceId, String system, String signalKind) {
        if (workspaceId <= 0 || blank(system) || blank(signalKind)) {
            return Optional.empty();
        }
        TroubleshootingEvidenceRouteEntity entity = row(
                workspaceId, normalize(system), normalize(signalKind));
        // 「声明了但平台为空」是一个答案——租户明说这一格不取证，不该被回落覆盖。
        return entity == null ? Optional.empty() : Optional.of(split(entity.getPlatforms()));
    }

    public List<EvidenceRouteView> list(long workspaceId, String system) {
        requireWorkspace(workspaceId);
        LambdaQueryWrapper<TroubleshootingEvidenceRouteEntity> query =
                new LambdaQueryWrapper<TroubleshootingEvidenceRouteEntity>()
                        .eq(TroubleshootingEvidenceRouteEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingEvidenceRouteEntity::getDeleted, 0)
                        .orderByAsc(TroubleshootingEvidenceRouteEntity::getSystemName)
                        .orderByAsc(TroubleshootingEvidenceRouteEntity::getSignalKind);
        if (!blank(system)) {
            query.eq(TroubleshootingEvidenceRouteEntity::getSystemName, normalize(system));
        }
        return mapper.selectList(query).stream().map(this::view).toList();
    }

    /** Declares (or replaces) exactly one (system, signalKind) route for this workspace. */
    @Transactional
    public EvidenceRouteView declare(
            long workspaceId,
            String system,
            String signalKind,
            List<String> platforms,
            String actor,
            String reason) {
        requireWorkspace(workspaceId);
        String safeSystem = safeName(system, "system");
        String safeSignal = safeName(signalKind, "signalKind");
        if (!CanonicalEvidenceSchema.supports(safeSignal)) {
            // 词表之外的 signal 永远取不到合法结果，声明它只可能是打错字。
            throw invalid("unknown signalKind '" + safeSignal + "'; this platform understands: "
                    + String.join(", ", CanonicalEvidenceSchema.signalKinds()));
        }
        List<String> safePlatforms = validPlatforms(platforms, safeSignal);
        String safeActor = safeName(actor, "actor");
        String safeReason = requireReason(reason);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TroubleshootingEvidenceRouteEntity existing = row(workspaceId, safeSystem, safeSignal);
        if (existing == null) {
            TroubleshootingEvidenceRouteEntity entity =
                    new TroubleshootingEvidenceRouteEntity();
            entity.setWorkspaceId(workspaceId);
            entity.setSystem(safeSystem);
            entity.setSignalKind(safeSignal);
            entity.setPlatforms(String.join(",", safePlatforms));
            entity.setUpdatedBy(safeActor);
            entity.setReason(safeReason);
            entity.setVersion(0);
            entity.setDeleted(0);
            entity.setCreateTime(now);
            entity.setUpdateTime(now);
            try {
                mapper.insert(entity);
            } catch (DuplicateKeyException raced) {
                // 唯一键是最终裁判：两条同格路由会让插入顺序决定打到哪个源。
                throw conflict("this route was declared concurrently; reload and retry");
            }
            return view(entity);
        }

        existing.setPlatforms(String.join(",", safePlatforms));
        existing.setUpdatedBy(safeActor);
        existing.setReason(safeReason);
        existing.setUpdateTime(now);
        existing.setVersion(existing.getVersion() == null ? 1 : existing.getVersion() + 1);
        mapper.updateById(existing);
        return view(existing);
    }

    /** Removes one declaration so the deployment-level route applies again. */
    @Transactional
    public void withdraw(long workspaceId, String system, String signalKind) {
        requireWorkspace(workspaceId);
        TroubleshootingEvidenceRouteEntity existing = row(
                workspaceId, safeName(system, "system"), safeName(signalKind, "signalKind"));
        if (existing == null) {
            throw new MateClawException(
                    "err.troubleshooting.evidence_route_not_found", 404,
                    "no declared route for this system and signal kind");
        }
        mapper.deleteById(existing.getId());
    }

    private List<String> validPlatforms(List<String> platforms, String signalKind) {
        List<String> known = adapters.stream()
                .map(EvidenceSourceAdapter::platform)
                .toList();
        List<String> resolved = new ArrayList<>();
        for (String raw : platforms == null ? List.<String>of() : platforms) {
            String candidate = safeName(raw, "platform");
            String match = known.stream()
                    .filter(name -> normalize(name).equals(candidate))
                    .findFirst()
                    .orElseThrow(() -> invalid(
                            "unknown evidence platform '" + candidate
                                    + "'; this deployment has: " + String.join(", ", known)));
            resolved.add(match);
        }
        List<String> unique = List.copyOf(new LinkedHashSet<>(resolved));
        if (unique.size() != resolved.size()) {
            // 同一个平台列两遍，只会让它失败两次，读起来却像有两条退路。
            throw invalid("a route must not list the same platform twice");
        }
        if (unique.size() > MAX_PLATFORMS) {
            throw invalid("a route may list at most " + MAX_PLATFORMS + " platforms");
        }
        return unique;
    }

    private EvidenceRouteView view(TroubleshootingEvidenceRouteEntity entity) {
        List<String> platforms = split(entity.getPlatforms());
        List<EvidenceRouteView.PlatformState> states = platforms.stream()
                .map(platform -> state(platform, entity.getSignalKind()))
                .toList();
        return new EvidenceRouteView(
                entity.getSystem(),
                entity.getSignalKind(),
                platforms,
                states,
                entity.getUpdatedBy(),
                entity.getReason(),
                entity.getUpdateTime() == null
                        ? null : entity.getUpdateTime().toInstant(ZoneOffset.UTC));
    }

    /**
     * 这个平台此刻能不能真的为这条 signal 取证。
     *
     * <p>两个条件都要成立：适配器自己报可用，**并且**它支持这条 signal。只看健康
     * 会给出「显示可用、实际取不到」——{@link EvidenceSourceRouter#collect} 里
     * 那条 {@code supports} 判断会把它跳过去，而调用方看到的却是绿的。</p>
     */
    private EvidenceRouteView.PlatformState state(String platform, String signalKind) {
        EvidenceSourceAdapter adapter = adapters.stream()
                .filter(item -> normalize(item.platform()).equals(normalize(platform)))
                .findFirst()
                .orElse(null);
        if (adapter == null) {
            return new EvidenceRouteView.PlatformState(
                    platform, false, "adapter is not present on this deployment");
        }
        EvidenceSourceHealth health;
        boolean supported;
        try {
            health = adapter.health();
            supported = adapter.supports(signalKind);
        } catch (RuntimeException failure) {
            // 探测自己坏了不能读成「可用」。
            return new EvidenceRouteView.PlatformState(
                    platform, false,
                    "health check failed: " + failure.getClass().getSimpleName());
        }
        if (health == null) {
            return new EvidenceRouteView.PlatformState(
                    platform, false, "source returned no health state");
        }
        if (!supported) {
            return new EvidenceRouteView.PlatformState(
                    platform, false, "adapter does not serve signal '" + signalKind + "'");
        }
        // READY 而已。verified（owner 是否验收过这个绑定）是另一条轴，由
        // EvidenceSourceAcceptanceService 回答；在这里混进来，读者就会拿
        // 「路由配好了」去推「证据可信」。
        return new EvidenceRouteView.PlatformState(
                platform,
                health.status() == EvidenceSourceHealth.Status.READY,
                health.detail());
    }

    private TroubleshootingEvidenceRouteEntity row(
            long workspaceId, String system, String signalKind) {
        return mapper.selectOne(
                new LambdaQueryWrapper<TroubleshootingEvidenceRouteEntity>()
                        .eq(TroubleshootingEvidenceRouteEntity::getWorkspaceId, workspaceId)
                        .eq(TroubleshootingEvidenceRouteEntity::getSystemName, system)
                        .eq(TroubleshootingEvidenceRouteEntity::getSignalKind, signalKind)
                        .eq(TroubleshootingEvidenceRouteEntity::getDeleted, 0));
    }

    private List<String> split(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return List.of(stored.split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    private String safeName(String value, String field) {
        if (blank(value)) {
            throw invalid(field + " must not be blank");
        }
        String trimmed = value.trim();
        if (!SAFE_NAME.matcher(trimmed).matches()) {
            throw invalid(field + " must be a bounded server-safe name");
        }
        return normalize(trimmed);
    }

    private String requireReason(String reason) {
        if (blank(reason)) {
            // 路由决定一条请求打到哪个生产观测系统。改动必须留下为什么。
            throw invalid("reason must not be blank");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > MAX_REASON
                || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw invalid("reason exceeds its text boundary");
        }
        return trimmed;
    }

    private void requireWorkspace(long workspaceId) {
        if (workspaceId <= 0) {
            throw invalid("workspaceId must be positive");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private MateClawException invalid(String message) {
        return new MateClawException(
                "err.troubleshooting.evidence_route_invalid", 400, message);
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.evidence_route_conflict", 409, message);
    }
}
