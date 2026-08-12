package vip.mate.troubleshooting.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.TroubleshootingOpenDiscoveryAgentBindingEntity;
import vip.mate.troubleshooting.repository.TroubleshootingOpenDiscoveryAgentBindingMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Durable workspace binding of the dedicated OPEN_DISCOVERY digital employee.
 *
 * <p>Resolves ahead of process-wide {@code mateclaw.troubleshooting.agent.agent-id}
 * so Operators can pick an employee from the Agents page without restarting.</p>
 */
@Service
public class OpenDiscoveryAgentBindingService {

    public record BindingView(
            long workspaceId,
            long agentId,
            String agentName,
            OpenDiscoveryAgentBindingSource source,
            String boundBy,
            LocalDateTime boundAt,
            List<String> blockers,
            boolean ready) {
    }

    private final TroubleshootingOpenDiscoveryAgentBindingMapper mapper;
    private final OpenDiscoveryAgentGate agentGate;
    private final AgentBindingService toolBindings;
    private final Clock clock;

    @Autowired
    public OpenDiscoveryAgentBindingService(
            TroubleshootingOpenDiscoveryAgentBindingMapper mapper,
            OpenDiscoveryAgentGate agentGate,
            AgentBindingService toolBindings) {
        this(mapper, agentGate, toolBindings, Clock.systemUTC());
    }

    OpenDiscoveryAgentBindingService(
            TroubleshootingOpenDiscoveryAgentBindingMapper mapper,
            OpenDiscoveryAgentGate agentGate,
            AgentBindingService toolBindings,
            Clock clock) {
        this.mapper = mapper;
        this.agentGate = agentGate;
        this.toolBindings = toolBindings;
        this.clock = clock;
    }

    public Optional<Long> findWorkspaceAgentId(long workspaceId) {
        TroubleshootingOpenDiscoveryAgentBindingEntity row = mapper.findByWorkspace(workspaceId);
        if (row == null || row.getAgentId() == null || row.getAgentId() <= 0) {
            return Optional.empty();
        }
        return Optional.of(row.getAgentId());
    }

    public BindingView current(long workspaceId) {
        OpenDiscoveryAgentGate.Inspection inspection = agentGate.inspect(workspaceId);
        OpenDiscoveryAgentBindingSource source = agentGate.bindingSource(workspaceId);
        AgentEntity agent = inspection.agent();
        TroubleshootingOpenDiscoveryAgentBindingEntity row = mapper.findByWorkspace(workspaceId);
        return new BindingView(
                workspaceId,
                agent == null ? agentGate.resolveAgentId(workspaceId) : agent.getId(),
                agent == null ? null : agent.getName(),
                source,
                row == null ? null : row.getBoundBy(),
                row == null ? null : row.getBoundAt(),
                inspection.blockers(),
                inspection.status() == OpenDiscoveryAgentGate.Status.AGENT_READY);
    }

    @Transactional
    public BindingView bind(
            long workspaceId,
            long agentId,
            String actor,
            boolean prepareEvidenceTool) {
        if (agentId <= 0) {
            throw new MateClawException(
                    "err.troubleshooting.agent_binding_invalid",
                    400,
                    "agentId must be a positive digital-employee id");
        }
        if (prepareEvidenceTool) {
            toolBindings.setToolBindings(agentId, List.of("TroubleshootingEvidenceTool"));
        }
        OpenDiscoveryAgentGate.Inspection candidate = agentGate.inspectCandidate(workspaceId, agentId);
        List<String> agentBlockers = candidate.blockers().stream()
                .filter(blocker -> !blocker.contains("开关未打开"))
                .toList();
        if (candidate.agent() == null || !agentBlockers.isEmpty()) {
            String reason = agentBlockers.isEmpty()
                    ? "candidate digital employee is unavailable"
                    : agentBlockers.getFirst();
            throw new MateClawException(
                    "err.troubleshooting.agent_binding_rejected",
                    409,
                    "cannot bind digital employee: " + reason);
        }

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        TroubleshootingOpenDiscoveryAgentBindingEntity existing = mapper.findByWorkspace(workspaceId);
        TroubleshootingOpenDiscoveryAgentBindingEntity row =
                existing == null ? new TroubleshootingOpenDiscoveryAgentBindingEntity() : existing;
        row.setWorkspaceId(workspaceId);
        row.setAgentId(agentId);
        row.setBoundBy(actor == null || actor.isBlank() ? "unknown" : actor.trim());
        row.setBoundAt(now);
        row.setUpdateTime(now);
        if (existing == null) {
            row.setCreateTime(now);
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
        return current(workspaceId);
    }

    @Transactional
    public BindingView clear(long workspaceId) {
        mapper.deleteByWorkspace(workspaceId);
        return current(workspaceId);
    }
}
