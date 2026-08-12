package vip.mate.troubleshooting.agent;

import org.springframework.stereotype.Component;
import vip.mate.agent.AgentService;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.exception.MateClawException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Secret-free configuration gate for the OPEN_DISCOVERY miss-path.
 *
 * <p>Inspects without calling a model or evidence source. Triage and readiness
 * share the same fail-closed rules so operators can see blockers before an
 * unknown alert arrives at night.</p>
 */
@Component
public final class OpenDiscoveryAgentGate {

    static final int MIN_PROMPT_CHARS = 4_096;
    static final Duration MAX_SYNC_TRIAGE_TIMEOUT = Duration.ofSeconds(25);
    static final Set<String> REQUIRED_BINDINGS = Set.of("TroubleshootingEvidenceTool");

    private final TroubleshootingAgentProperties properties;
    private final AgentService agentService;
    private final AgentBindingService bindingService;

    public OpenDiscoveryAgentGate(
            TroubleshootingAgentProperties properties,
            AgentService agentService,
            AgentBindingService bindingService) {
        this.properties = properties;
        this.agentService = agentService;
        this.bindingService = bindingService;
    }

    public Inspection inspect(long workspaceId) {
        List<String> blockers = new ArrayList<>();
        if (!properties.isEnabled()) {
            blockers.add("开放调查开关未打开（mateclaw.troubleshooting.agent.enabled）");
        }
        if (properties.getAgentId() <= 0) {
            blockers.add("未配置专用 Agent ID（mateclaw.troubleshooting.agent.agent-id）");
        }
        if (properties.getMaxIterations() <= 0) {
            blockers.add("Agent 迭代上限未配置或非法");
        }
        if (properties.getMaxEvidenceRequests() < 3) {
            blockers.add("单次调查至少需要 3 次取证预算（日志→链路→对照）");
        }
        if (properties.getMaxPromptChars() < MIN_PROMPT_CHARS) {
            blockers.add("提示词字符预算过低（至少 " + MIN_PROMPT_CHARS + "）");
        }
        if (properties.getTriageTimeout() == null
                || properties.getTriageTimeout().toMillis() <= 0
                || properties.getTriageTimeout().compareTo(MAX_SYNC_TRIAGE_TIMEOUT) > 0) {
            blockers.add("同步调查超时未配置，或超过 " + MAX_SYNC_TRIAGE_TIMEOUT.toSeconds() + " 秒上限");
        }

        AgentEntity agent = null;
        if (properties.getAgentId() > 0) {
            try {
                agent = agentService.getAgent(properties.getAgentId());
            } catch (RuntimeException unavailable) {
                blockers.add("配置的专用 Agent 不可用或不存在");
            }
        }
        if (agent != null) {
            if (!Boolean.TRUE.equals(agent.getEnabled())) {
                blockers.add("专用 Agent 未启用");
            }
            if (agent.getWorkspaceId() == null || agent.getWorkspaceId() != workspaceId) {
                blockers.add("专用 Agent 不属于当前 Workspace");
            }
            if (!"react".equalsIgnoreCase(safe(agent.getAgentType()))) {
                blockers.add("专用 Agent 必须是 ReAct 类型");
            }
            if (agent.getModelName() == null || agent.getModelName().isBlank()) {
                blockers.add("专用 Agent 必须显式绑定唯一模型");
            }
            if (!Boolean.TRUE.equals(agent.getSkillsDisabled())) {
                blockers.add("专用 Agent 必须关闭 Skills");
            }
            if (!Boolean.TRUE.equals(agent.getWikiDisabled())) {
                blockers.add("专用 Agent 必须关闭 Wiki");
            }
            if (Boolean.TRUE.equals(agent.getToolsDisabled())) {
                blockers.add("专用 Agent 不能关闭工具（需要唯一只读取证工具）");
            }
            if (agent.getMaxIterations() == null
                    || agent.getMaxIterations() <= 0
                    || agent.getMaxIterations() > properties.getMaxIterations()) {
                blockers.add("专用 Agent 迭代数必须在配置上限内且大于 0");
            }
            Set<String> bindings = bindingService.getBoundToolNames(agent.getId());
            if (!REQUIRED_BINDINGS.equals(bindings)) {
                blockers.add("专用 Agent 必须且只能绑定 TroubleshootingEvidenceTool");
            }
        }

        Status status;
        if (!properties.isEnabled()) {
            status = Status.DISABLED;
        } else if (!blockers.isEmpty()) {
            status = Status.MISCONFIGURED;
        } else {
            status = Status.AGENT_READY;
        }
        return new Inspection(status, List.copyOf(blockers), agent);
    }

    /** Same fail-closed rules triage uses before any model call. */
    public AgentEntity requireReadyAgent(long workspaceId) {
        if (!properties.isEnabled()) {
            throw configurationConflict("troubleshooting miss-path Agent is disabled");
        }
        if (properties.getAgentId() <= 0
                || properties.getMaxIterations() <= 0
                || properties.getMaxEvidenceRequests() < 3
                || properties.getMaxPromptChars() < MIN_PROMPT_CHARS
                || properties.getTriageTimeout() == null
                || properties.getTriageTimeout().toMillis() <= 0
                || properties.getTriageTimeout().compareTo(MAX_SYNC_TRIAGE_TIMEOUT) > 0) {
            throw configurationConflict("troubleshooting Agent limits are not configured");
        }
        Inspection inspection = inspect(workspaceId);
        if (inspection.status() != Status.AGENT_READY || inspection.agent() == null) {
            AgentEntity agent = inspection.agent();
            if (agent == null) {
                throw configurationConflict("configured troubleshooting Agent is unavailable");
            }
            Set<String> bindings = bindingService.getBoundToolNames(agent.getId());
            if (!REQUIRED_BINDINGS.equals(bindings)) {
                throw configurationConflict(
                        "troubleshooting Agent requires exactly one read-only tool binding");
            }
            throw configurationConflict(
                    "troubleshooting Agent must be enabled, workspace-local, ReAct, "
                            + "explicitly model-pinned, skill/wiki-disabled, and iteration-bounded");
        }
        return inspection.agent();
    }

    private MateClawException configurationConflict(String message) {
        return new MateClawException(
                "err.troubleshooting.agent_misconfigured", 409, message);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public enum Status {
        DISABLED,
        MISCONFIGURED,
        AGENT_READY
    }

    public record Inspection(
            Status status,
            List<String> blockers,
            AgentEntity agent) {

        public Inspection {
            status = status == null ? Status.MISCONFIGURED : status;
            blockers = List.copyOf(blockers == null ? List.of() : blockers);
        }
    }
}
