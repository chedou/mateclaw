package vip.mate.troubleshooting.evidence;

import java.util.List;
import java.util.Optional;

/**
 * 这个 workspace 自己声明的取证路由。
 *
 * <p><b>为什么要有这条缝。</b> 路由此前只有一份，在 {@code application.yml} 里。
 * 后果有两条，都很坏：新租户注册完 Playbook、批准、开案、跑取证计划，每一条证据
 * 都回 {@code MISSING/router:unconfigured}——**接一个系统要改发布物里的文件**；
 * 而且那张表只按 system 名字索引，任何 workspace 只要把系统命名成 {@code CSDP}
 * 就继承了 CSDP 的路由，打到 CSDP 的观测端点上。</p>
 *
 * <p><b>它是接口而不是直接查库</b>，是为了让 {@link EvidenceSourceRouter} 保持可以
 * 脱库单测——路由是决定「一条请求打到哪个生产观测系统」的东西，它的分支必须能被
 * 便宜地穷举，否则就没人会去穷举。</p>
 */
@FunctionalInterface
public interface WorkspaceEvidenceRoutes {

    /** 空实现：没有任何 workspace 级路由，行为与本特性引入之前完全一致。 */
    WorkspaceEvidenceRoutes NONE = (workspaceId, system, signalKind) -> Optional.empty();

    /**
     * @return 有序的平台名单；{@code Optional.empty()} 表示这个 workspace 没有为
     *         这一格声明过路由，调用方应当继续回落到部署级配置。注意与「声明了
     *         但列表为空」不同——后者是租户明确说「这一格不取证」，是一个答案。
     */
    Optional<List<String>> find(long workspaceId, String system, String signalKind);
}
