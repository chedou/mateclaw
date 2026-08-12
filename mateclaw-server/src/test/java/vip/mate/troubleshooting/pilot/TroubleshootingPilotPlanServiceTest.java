package vip.mate.troubleshooting.pilot;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.model.TroubleshootingPilotPlanEntity;
import vip.mate.troubleshooting.repository.TroubleshootingPilotPlanMapper;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.service.WorkspaceService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TroubleshootingPilotPlanServiceTest {

    private static final long WORKSPACE_ID = 7L;
    private static final long SECOND_LINE = 11L;
    private static final long THIRD_LINE = 12L;
    private static final long SOURCE_OWNER = 13L;

    private final List<TroubleshootingPilotPlanEntity> rows = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private TroubleshootingPilotPlanService service;
    private WorkspaceService workspaces;
    private AuthService users;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                TroubleshootingPilotPlanEntity.class);
    }

    @BeforeEach
    void setUp() {
        workspaces = mock(WorkspaceService.class);
        users = mock(AuthService.class);
        registerMember(SECOND_LINE, "ops-l2", "二线小周", "member");
        registerMember(THIRD_LINE, "dev-l3", "三线小陈", "admin");
        registerMember(SOURCE_OWNER, "guance-owner", "观测负责人", "admin");
        service = new TroubleshootingPilotPlanService(
                mapper(), workspaces, users, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-13T03:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void declaresAnExactPilotScopeUsingExistingWorkspaceMembers() {
        TroubleshootingPilotPlanService.PlanView plan = service.declare(
                WORKSPACE_ID, declaration(0), "admin");

        assertThat(plan.configured()).isTrue();
        assertThat(plan.enabled()).isTrue();
        assertThat(plan.version()).isEqualTo(1);
        assertThat(plan.modules()).containsExactly(
                new TroubleshootingPilotPlanService.ModuleScope("csdp", "csdp-task"),
                new TroubleshootingPilotPlanService.ModuleScope("csdp", "csdp-wechat"));
        assertThat(plan.secondLine().displayName()).isEqualTo("二线小周");
        assertThat(plan.thirdLine().displayName()).isEqualTo("三线小陈");
        assertThat(plan.sourceOwner().displayName()).isEqualTo("观测负责人");
        assertThat(plan.blockers()).isEmpty();
    }

    @Test
    void appendsImmutableVersionsAndRejectsAStaleEditor() {
        service.declare(WORKSPACE_ID, declaration(0), "admin");

        TroubleshootingPilotPlanService.PlanView updated = service.declare(
                WORKSPACE_ID,
                new TroubleshootingPilotPlanService.Declaration(
                        "CSDP 首批试点",
                        List.of(new TroubleshootingPilotPlanService.ModuleScope(
                                "CSDP", "csdp-wechat")),
                        SECOND_LINE, THIRD_LINE, SOURCE_OWNER, true, 1,
                        "先收敛到 ITGW 场景"),
                "owner");

        assertThat(updated.version()).isEqualTo(2);
        assertThat(rows).hasSize(2)
                .extracting(TroubleshootingPilotPlanEntity::getVersion)
                .containsExactly(1, 2);
        assertThatThrownBy(() -> service.declare(
                WORKSPACE_ID, declaration(1), "stale-editor"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("version");
    }

    @Test
    void refusesMissingMembersRoleCollapseAndDuplicateModules() {
        when(workspaces.getMembership(WORKSPACE_ID, 99L)).thenReturn(null);

        assertThatThrownBy(() -> service.declare(
                WORKSPACE_ID,
                new TroubleshootingPilotPlanService.Declaration(
                        "CSDP 首批试点", modules(), 99L, THIRD_LINE, SOURCE_OWNER,
                        true, 0, "指定试点人员"),
                "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("workspace member");

        assertThatThrownBy(() -> service.declare(
                WORKSPACE_ID,
                new TroubleshootingPilotPlanService.Declaration(
                        "CSDP 首批试点", modules(), SECOND_LINE, SECOND_LINE, SOURCE_OWNER,
                        true, 0, "指定试点人员"),
                "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("distinct");

        assertThatThrownBy(() -> service.declare(
                WORKSPACE_ID,
                new TroubleshootingPilotPlanService.Declaration(
                        "CSDP 首批试点",
                        List.of(
                                new TroubleshootingPilotPlanService.ModuleScope(
                                        "CSDP", "csdp-wechat"),
                                new TroubleshootingPilotPlanService.ModuleScope(
                                        "csdp", "CSDP-WECHAT")),
                        SECOND_LINE, THIRD_LINE, SOURCE_OWNER,
                        true, 0, "指定试点人员"),
                "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void refusesDisabledWorkspaceMembers() {
        UserEntity disabled = new UserEntity();
        disabled.setId(SOURCE_OWNER);
        disabled.setUsername("disabled-owner");
        disabled.setEnabled(false);
        when(users.findById(SOURCE_OWNER)).thenReturn(disabled);

        assertThatThrownBy(() -> service.declare(
                WORKSPACE_ID, declaration(0), "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("active workspace member");
    }

    @Test
    void refusesOwnersWhoCannotPerformTheirAssignedPilotActions() {
        registerMember(SECOND_LINE, "ops-l2", "二线小周", "viewer");

        assertThatThrownBy(() -> service.declare(
                WORKSPACE_ID, declaration(0), "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("second-line owner")
                .hasMessageContaining("member");

        registerMember(SECOND_LINE, "ops-l2", "二线小周", "member");
        registerMember(THIRD_LINE, "dev-l3", "三线小陈", "member");

        assertThatThrownBy(() -> service.declare(
                WORKSPACE_ID, declaration(0), "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("third-line reviewer")
                .hasMessageContaining("admin");

        registerMember(THIRD_LINE, "dev-l3", "三线小陈", "admin");
        registerMember(SOURCE_OWNER, "guance-owner", "观测负责人", "member");

        assertThatThrownBy(() -> service.declare(
                WORKSPACE_ID, declaration(0), "admin"))
                .isInstanceOf(MateClawException.class)
                .hasMessageContaining("source owner")
                .hasMessageContaining("admin");
    }

    @Test
    void reportsAnUnconfiguredPlanWithoutInventingPeopleOrScope() {
        TroubleshootingPilotPlanService.PlanView plan = service.current(WORKSPACE_ID);

        assertThat(plan.configured()).isFalse();
        assertThat(plan.version()).isZero();
        assertThat(plan.modules()).isEmpty();
        assertThat(plan.secondLine()).isNull();
        assertThat(plan.blockers()).contains(
                "试点范围尚未配置",
                "二线、三线和系统负责人尚未固定");
    }

    @Test
    void marksASavedPlanUnavailableWhenAnOwnerLosesTheRequiredRole() {
        service.declare(WORKSPACE_ID, declaration(0), "admin");
        registerMember(THIRD_LINE, "dev-l3", "三线小陈", "member");

        TroubleshootingPilotPlanService.PlanView plan = service.current(WORKSPACE_ID);

        assertThat(plan.configured()).isTrue();
        assertThat(plan.blockers()).contains(
                "三线复核人需要管理员或所有者角色，才能维护人工答案和评估结果");

        registerMember(THIRD_LINE, "dev-l3", "三线小陈", "admin");
        registerMember(SOURCE_OWNER, "guance-owner", "观测负责人", "member");
        assertThat(service.current(WORKSPACE_ID).blockers()).contains(
                "数据取证负责人需要管理员或所有者角色，才能采集真源样本");

        registerMember(SOURCE_OWNER, "guance-owner", "观测负责人", "admin");
        registerMember(SECOND_LINE, "ops-l2", "二线小周", "viewer");
        assertThat(service.current(WORKSPACE_ID).blockers()).contains(
                "二线闭环人需要成员、管理员或所有者角色，才能推进排障单");
    }

    private TroubleshootingPilotPlanService.Declaration declaration(int expectedVersion) {
        return new TroubleshootingPilotPlanService.Declaration(
                "CSDP 首批试点", modules(), SECOND_LINE, THIRD_LINE, SOURCE_OWNER,
                true, expectedVersion, "固定首批范围与三类试点人员");
    }

    private List<TroubleshootingPilotPlanService.ModuleScope> modules() {
        return List.of(
                new TroubleshootingPilotPlanService.ModuleScope("CSDP", "csdp-wechat"),
                new TroubleshootingPilotPlanService.ModuleScope("CSDP", "csdp-task"));
    }

    private void registerMember(long userId, String username, String nickname, String role) {
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspaceId(WORKSPACE_ID);
        member.setUserId(userId);
        member.setRole(role);
        when(workspaces.getMembership(WORKSPACE_ID, userId)).thenReturn(member);

        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setUsername(username);
        user.setNickname(nickname);
        user.setEnabled(true);
        when(users.findById(userId)).thenReturn(user);
    }

    @SuppressWarnings("unchecked")
    private TroubleshootingPilotPlanMapper mapper() {
        TroubleshootingPilotPlanMapper mapper = mock(TroubleshootingPilotPlanMapper.class);
        when(mapper.insert(any(TroubleshootingPilotPlanEntity.class)))
                .thenAnswer((Answer<Integer>) invocation -> {
                    TroubleshootingPilotPlanEntity row = invocation.getArgument(0);
                    row.setId(ids.incrementAndGet());
                    rows.add(row);
                    return 1;
                });
        when(mapper.findLatestByWorkspace(anyLong())).thenAnswer(invocation -> rows.stream()
                .max((left, right) -> Integer.compare(
                        left.getVersion(), right.getVersion()))
                .orElse(null));
        return mapper;
    }
}
