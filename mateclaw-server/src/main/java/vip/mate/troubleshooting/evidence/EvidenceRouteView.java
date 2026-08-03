package vip.mate.troubleshooting.evidence;

import java.time.Instant;
import java.util.List;

/**
 * 一条已声明的取证路由，外加**每个平台此刻是否真的能取**。
 *
 * <p><b>为什么把可用性一起返回。</b> 路由是一条持久声明，而源的健康是会变的。
 * 只回显声明，调用方会以为「配好了就能取」；而路由指向一个当前关着的源时，取证
 * 会安静地回 MISSING——安静正是最难查的那种坏。声明成功仍然算成功，但这里把话
 * 说全：你配的是这几个，现在能取的是这几个。</p>
 *
 * <p>只报平台名和可用与否，不报端点、不报凭据。</p>
 */
public record EvidenceRouteView(
        String system,
        String signalKind,
        List<String> platforms,
        List<PlatformState> platformStates,
        String updatedBy,
        String reason,
        Instant updatedAt) {

    public EvidenceRouteView {
        platforms = List.copyOf(platforms == null ? List.of() : platforms);
        platformStates = List.copyOf(platformStates == null ? List.of() : platformStates);
        if (system == null || system.isBlank()
                || signalKind == null || signalKind.isBlank()) {
            throw new IllegalArgumentException("system and signalKind are required");
        }
        if (platformStates.size() != platforms.size()) {
            // 两个清单必须一一对应，否则读者会把第 n 个平台的可用性读到别人头上。
            throw new IllegalArgumentException(
                    "every routed platform needs exactly one availability state");
        }
    }

    /**
     * @param available 该平台此刻能否真的为这条 signal 取到证据（适配器 READY 且
     *                  声明支持这条 signal）。**它不表示这个源已被 owner 验收**
     *                  ——那是另一条轴，混进来会让人拿「配好了」去推「可信」。
     */
    public record PlatformState(String platform, boolean available, String detail) {
        public PlatformState {
            if (platform == null || platform.isBlank()) {
                throw new IllegalArgumentException("platform must not be blank");
            }
            detail = detail == null ? "" : detail;
        }
    }
}
