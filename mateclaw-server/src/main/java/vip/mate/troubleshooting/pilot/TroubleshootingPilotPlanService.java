package vip.mate.troubleshooting.pilot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.TroubleshootingBusinessTextPolicy;
import vip.mate.troubleshooting.TroubleshootingSecretRedactor;
import vip.mate.troubleshooting.model.TroubleshootingPilotPlanEntity;
import vip.mate.troubleshooting.repository.TroubleshootingPilotPlanMapper;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.service.WorkspaceService;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Workspace-scoped declaration of the first troubleshooting pilot.
 *
 * <p>Every change appends a new revision. The plan deliberately stores only
 * exact system/service scope and existing Workspace member identifiers; it does
 * not invent scenario coverage or duplicate the Diagnosis/evaluation ledger.</p>
 */
@Service
public class TroubleshootingPilotPlanService {

    private static final int MAX_MODULES = 20;
    private static final int MAX_NAME_CHARS = 120;
    private static final int MAX_REASON_CHARS = 300;
    private static final Pattern IDENTIFIER = Pattern.compile(
            "[a-z0-9](?:[a-z0-9._-]{0,126}[a-z0-9])?");
    private static final TypeReference<List<ModuleScope>> MODULE_LIST =
            new TypeReference<>() { };

    public record ModuleScope(String system, String service) {
    }

    public record MemberView(
            long userId,
            String username,
            String nickname,
            String displayName,
            String workspaceRole) {
    }

    public record Declaration(
            String name,
            List<ModuleScope> modules,
            long secondLineUserId,
            long thirdLineUserId,
            long sourceOwnerUserId,
            boolean enabled,
            int expectedVersion,
            String reason) {
    }

    public record PlanView(
            long workspaceId,
            boolean configured,
            boolean enabled,
            int version,
            String name,
            List<ModuleScope> modules,
            MemberView secondLine,
            MemberView thirdLine,
            MemberView sourceOwner,
            String changedBy,
            LocalDateTime changedAt,
            String changeReason,
            List<String> blockers) {
    }

    private final TroubleshootingPilotPlanMapper mapper;
    private final WorkspaceService workspaces;
    private final AuthService users;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public TroubleshootingPilotPlanService(
            TroubleshootingPilotPlanMapper mapper,
            WorkspaceService workspaces,
            AuthService users,
            ObjectMapper objectMapper) {
        this(mapper, workspaces, users, objectMapper, Clock.systemUTC());
    }

    TroubleshootingPilotPlanService(
            TroubleshootingPilotPlanMapper mapper,
            WorkspaceService workspaces,
            AuthService users,
            ObjectMapper objectMapper,
            Clock clock) {
        this.mapper = mapper;
        this.workspaces = workspaces;
        this.users = users;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public PlanView current(long workspaceId) {
        requireWorkspace(workspaceId);
        TroubleshootingPilotPlanEntity row = latest(workspaceId);
        if (row == null) {
            return new PlanView(
                    workspaceId, false, false, 0, null, List.of(),
                    null, null, null, null, null, null,
                    List.of("\u8bd5\u70b9\u8303\u56f4\u5c1a\u672a\u914d\u7f6e", "\u4e8c\u7ebf\u3001\u4e09\u7ebf\u548c\u7cfb\u7edf\u8d1f\u8d23\u4eba\u5c1a\u672a\u56fa\u5b9a"));
        }
        return project(row);
    }

    @Transactional
    public PlanView declare(long workspaceId, Declaration declaration, String actor) {
        requireWorkspace(workspaceId);
        if (declaration == null) {
            throw invalid("pilot declaration is required");
        }
        TroubleshootingPilotPlanEntity current = latest(workspaceId);
        int currentVersion = current == null ? 0 : current.getVersion();
        if (declaration.expectedVersion() != currentVersion) {
            throw conflict("pilot plan version changed; reload before saving");
        }

        String name = safeText(declaration.name(), "pilot plan name", MAX_NAME_CHARS, true);
        String reason = safeText(declaration.reason(), "change reason", MAX_REASON_CHARS, true);
        List<ModuleScope> modules = normalizeModules(declaration.modules());
        requireDistinctPeople(declaration);
        MemberView secondLine = requireActiveMember(
                workspaceId, declaration.secondLineUserId(), "second-line owner");
        MemberView thirdLine = requireActiveMember(
                workspaceId, declaration.thirdLineUserId(), "third-line reviewer");
        MemberView sourceOwner = requireActiveMember(
                workspaceId, declaration.sourceOwnerUserId(), "source owner");

        TroubleshootingPilotPlanEntity row = new TroubleshootingPilotPlanEntity();
        row.setWorkspaceId(workspaceId);
        row.setPlanName(name);
        row.setModuleScopes(writeModules(modules));
        row.setSecondLineUserId(secondLine.userId());
        row.setThirdLineUserId(thirdLine.userId());
        row.setSourceOwnerUserId(sourceOwner.userId());
        row.setEnabled(declaration.enabled());
        row.setVersion(currentVersion + 1);
        row.setChangedBy(safeActor(actor));
        row.setChangeReason(reason);
        row.setCreateTime(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException duplicate) {
            throw conflict("pilot plan version changed; reload before saving");
        }
        return project(row);
    }

    private TroubleshootingPilotPlanEntity latest(long workspaceId) {
        return mapper.findLatestByWorkspace(workspaceId);
    }

    private PlanView project(TroubleshootingPilotPlanEntity row) {
        List<String> blockers = new ArrayList<>();
        List<ModuleScope> modules = readModules(row.getModuleScopes(), blockers);
        if (modules.isEmpty()) {
            blockers.add("\u8bd5\u70b9\u8303\u56f4\u5c1a\u672a\u914d\u7f6e");
        }
        MemberView secondLine = resolveActiveMember(
                row.getWorkspaceId(), row.getSecondLineUserId());
        MemberView thirdLine = resolveActiveMember(
                row.getWorkspaceId(), row.getThirdLineUserId());
        MemberView sourceOwner = resolveActiveMember(
                row.getWorkspaceId(), row.getSourceOwnerUserId());
        if (secondLine == null || thirdLine == null || sourceOwner == null) {
            blockers.add("\u8bd5\u70b9\u8d1f\u8d23\u4eba\u5df2\u79bb\u5f00\u5de5\u4f5c\u533a\u6216\u8d26\u53f7\u5df2\u505c\u7528");
        }
        if (!Boolean.TRUE.equals(row.getEnabled())) {
            blockers.add("\u8bd5\u70b9\u8ba1\u5212\u5df2\u505c\u7528");
        }
        return new PlanView(
                row.getWorkspaceId(), true, Boolean.TRUE.equals(row.getEnabled()),
                row.getVersion(), row.getPlanName(), modules,
                secondLine, thirdLine, sourceOwner,
                row.getChangedBy(), row.getCreateTime(), row.getChangeReason(),
                List.copyOf(blockers));
    }

    private List<ModuleScope> normalizeModules(List<ModuleScope> modules) {
        if (modules == null || modules.isEmpty()) {
            throw invalid("at least one pilot module is required");
        }
        if (modules.size() > MAX_MODULES) {
            throw invalid("pilot modules must contain at most " + MAX_MODULES + " entries");
        }
        Set<String> seen = new HashSet<>();
        List<ModuleScope> normalized = new ArrayList<>();
        for (ModuleScope module : modules) {
            if (module == null) {
                throw invalid("pilot module is required");
            }
            String system = identifier(module.system(), "system");
            String service = identifier(module.service(), "service");
            String key = system + "\u0000" + service;
            if (!seen.add(key)) {
                throw invalid("duplicate pilot module: " + system + "/" + service);
            }
            normalized.add(new ModuleScope(system, service));
        }
        normalized.sort((left, right) -> {
            int systemOrder = left.system().compareTo(right.system());
            return systemOrder == 0
                    ? left.service().compareTo(right.service())
                    : systemOrder;
        });
        return List.copyOf(normalized);
    }

    private void requireDistinctPeople(Declaration declaration) {
        Set<Long> roles = new HashSet<>();
        roles.add(declaration.secondLineUserId());
        roles.add(declaration.thirdLineUserId());
        roles.add(declaration.sourceOwnerUserId());
        if (roles.size() != 3) {
            throw invalid("pilot role owners must be three distinct workspace members");
        }
    }

    private MemberView requireActiveMember(long workspaceId, long userId, String role) {
        MemberView member = resolveActiveMember(workspaceId, userId);
        if (member == null) {
            throw invalid(role + " must be an active workspace member");
        }
        return member;
    }

    private MemberView resolveActiveMember(long workspaceId, Long userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        WorkspaceMemberEntity membership = workspaces.getMembership(workspaceId, userId);
        UserEntity user = users.findById(userId);
        if (membership == null || user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            return null;
        }
        String username = safeDisplay(user.getUsername());
        String nickname = safeDisplay(user.getNickname());
        String displayName = nickname == null ? username : nickname;
        if (displayName == null) {
            displayName = "\u6210\u5458 " + userId;
        }
        return new MemberView(
                userId, username, nickname, displayName, safeDisplay(membership.getRole()));
    }

    private String identifier(String value, String field) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw invalid(field + " must be a stable lowercase identifier");
        }
        return normalized;
    }

    private String safeText(String value, String field, int maxChars, boolean required) {
        String normalized = value == null ? "" : value.trim();
        if (required && normalized.isBlank()) {
            throw invalid(field + " is required");
        }
        if (normalized.length() > maxChars) {
            throw invalid(field + " must be at most " + maxChars + " characters");
        }
        if (!TroubleshootingSecretRedactor.redact(normalized).equals(normalized)) {
            throw invalid(field + " must not contain credentials");
        }
        try {
            TroubleshootingBusinessTextPolicy.requireNoDeveloperEvidence(normalized, field);
        } catch (IllegalArgumentException unsafe) {
            throw invalid(unsafe.getMessage());
        }
        return normalized;
    }

    private String safeActor(String actor) {
        String normalized = actor == null || actor.isBlank() ? "unknown" : actor.trim();
        if (normalized.length() > 128) {
            normalized = normalized.substring(0, 128);
        }
        String redacted = TroubleshootingSecretRedactor.redact(normalized);
        return redacted.equals(normalized) ? normalized : "unknown";
    }

    private String safeDisplay(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        String redacted = TroubleshootingSecretRedactor.redact(normalized);
        return redacted.equals(normalized) ? normalized : null;
    }

    private String writeModules(List<ModuleScope> modules) {
        try {
            return objectMapper.writeValueAsString(modules);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("cannot serialize pilot modules", impossible);
        }
    }

    private List<ModuleScope> readModules(String encoded, List<String> blockers) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        try {
            return normalizeModules(objectMapper.readValue(encoded, MODULE_LIST));
        } catch (JsonProcessingException | MateClawException malformed) {
            blockers.add("\u8bd5\u70b9\u8303\u56f4\u8bb0\u5f55\u65e0\u6cd5\u8bfb\u53d6");
            return List.of();
        }
    }

    private void requireWorkspace(long workspaceId) {
        if (workspaceId <= 0) {
            throw invalid("workspaceId must be positive");
        }
    }

    private MateClawException invalid(String message) {
        return new MateClawException("err.troubleshooting.pilot_plan_invalid", 400, message);
    }

    private MateClawException conflict(String message) {
        return new MateClawException("err.troubleshooting.pilot_plan_conflict", 409, message);
    }
}
