package vip.mate.workspace.core.service;

import org.junit.jupiter.api.Test;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.core.model.WorkspaceEntity;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.repository.WorkspaceMapper;
import vip.mate.workspace.core.repository.WorkspaceMemberMapper;
import vip.mate.wiki.service.WikiKnowledgeBaseService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkspaceServiceRoleValidationTest {

    private final WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
    private final WorkspaceMemberMapper memberMapper = mock(WorkspaceMemberMapper.class);
    private final ConversationMapper conversationMapper = mock(ConversationMapper.class);
    private final WikiKnowledgeBaseService wikiKnowledgeBaseService = mock(WikiKnowledgeBaseService.class);
    private final AuthService authService = mock(AuthService.class);
    private final WorkspaceService service = new WorkspaceService(
            workspaceMapper, memberMapper, conversationMapper, wikiKnowledgeBaseService, null, authService);

    @Test
    void addMemberRejectsOwnerRole() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(1L);
        when(workspaceMapper.selectById(1L)).thenReturn(workspace);
        when(memberMapper.selectOne(any())).thenReturn(null);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.addMember(1L, 42L, "owner"));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.invalid_member_role", ex.getMsgKey());
        verify(memberMapper, never()).insert(any(WorkspaceMemberEntity.class));
    }

    @Test
    void updateMemberRoleRejectsOwnerEscalation() {
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspaceId(1L);
        member.setUserId(42L);
        member.setRole("admin");
        when(memberMapper.selectOne(any())).thenReturn(member);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.updateMemberRole(1L, 42L, "owner"));

        assertEquals(400, ex.getCode());
        assertEquals("err.workspace.invalid_member_role", ex.getMsgKey());
        verify(memberMapper, never()).updateById(any(WorkspaceMemberEntity.class));
    }

    @Test
    void addMemberDefaultsMissingRoleToMember() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(1L);
        when(workspaceMapper.selectById(1L)).thenReturn(workspace);
        when(memberMapper.selectOne(any())).thenReturn(null);

        WorkspaceMemberEntity member = service.addMember(1L, 42L, null);

        assertEquals("member", member.getRole());
        verify(memberMapper).insert(any(WorkspaceMemberEntity.class));
    }

    @Test
    void updateMemberRoleRejectsUnknownRole() {
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspaceId(1L);
        member.setUserId(42L);
        member.setRole("member");
        when(memberMapper.selectOne(any())).thenReturn(member);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> service.updateMemberRole(1L, 42L, "superuser"));

        assertEquals(400, ex.getCode());
        verify(memberMapper, never()).updateById(any(WorkspaceMemberEntity.class));
    }

    @Test
    void addMemberByUsernameValidatesRoleBeforeCreatingAnAccount() {
        MateClawException ex = assertThrows(MateClawException.class, () -> service.addMemberByUsername(
                1L, "invalid-role-user", "password", null, "owner", true, true));

        assertEquals("err.workspace.invalid_member_role", ex.getMsgKey());
        verify(authService, never()).createUser(any());
    }

    @Test
    void addMemberByUsernameRequiresGlobalAdminForAccountCreation() {
        MateClawException ex = assertThrows(MateClawException.class, () -> service.addMemberByUsername(
                1L, "new-user", "password", null, "member", true, false));

        assertEquals(403, ex.getCode());
        assertEquals("err.workspace.insufficient_permission", ex.getMsgKey());
        verify(authService, never()).findByUsername(any());
        verify(authService, never()).createUser(any());
    }

    @Test
    void addMemberByUsernameCreatesAndAddsWithinTheServiceTransaction() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(1L);
        UserEntity created = new UserEntity();
        created.setId(88L);
        when(workspaceMapper.selectById(1L)).thenReturn(workspace);
        when(memberMapper.selectOne(any())).thenReturn(null);
        when(authService.findByUsername("new-user")).thenReturn(null);
        when(authService.createUser(any(UserEntity.class))).thenReturn(created);

        WorkspaceMemberEntity member = service.addMemberByUsername(
                1L, "new-user", "password", "New User", "admin", true, true);

        assertEquals(88L, member.getUserId());
        assertEquals("admin", member.getRole());
        verify(authService).createUser(argThat(user -> "new-user".equals(user.getUsername())
                && "password".equals(user.getPassword())
                && "New User".equals(user.getNickname())));
        verify(memberMapper).insert(any(WorkspaceMemberEntity.class));
    }

    @Test
    void addMemberByUsernamePreservesLegacyPasswordCreationForGlobalAdmin() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(1L);
        UserEntity created = new UserEntity();
        created.setId(66L);
        when(workspaceMapper.selectById(1L)).thenReturn(workspace);
        when(memberMapper.selectOne(any())).thenReturn(null);
        when(authService.findByUsername("legacy-user")).thenReturn(null);
        when(authService.createUser(any(UserEntity.class))).thenReturn(created);

        WorkspaceMemberEntity member = service.addMemberByUsername(
                1L, "legacy-user", "legacy-password", null, "member", null, true);

        assertEquals(66L, member.getUserId());
        verify(authService).createUser(any(UserEntity.class));
    }

    @Test
    void addMemberByUsernamePreservesLegacyExistingAccountLinkForWorkspaceAdmin() {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(1L);
        UserEntity existing = new UserEntity();
        existing.setId(44L);
        when(workspaceMapper.selectById(1L)).thenReturn(workspace);
        when(memberMapper.selectOne(any())).thenReturn(null);
        when(authService.findByUsername("existing-user")).thenReturn(existing);

        WorkspaceMemberEntity member = service.addMemberByUsername(
                1L, "existing-user", "ignored-legacy-password", null, "member", null, false);

        assertEquals(44L, member.getUserId());
        verify(authService, never()).createUser(any());
        verify(memberMapper).insert(any(WorkspaceMemberEntity.class));
    }

    @Test
    void addMemberByUsernameRejectsExplicitCreationForAnExistingAccount() {
        UserEntity existing = new UserEntity();
        existing.setId(55L);
        when(authService.findByUsername("existing-user")).thenReturn(existing);

        MateClawException ex = assertThrows(MateClawException.class, () -> service.addMemberByUsername(
                1L, "existing-user", "password", null, "member", true, true));

        assertEquals("err.auth.username_exists", ex.getMsgKey());
        verify(authService, never()).createUser(any());
        verify(memberMapper, never()).insert(any(WorkspaceMemberEntity.class));
    }

    @Test
    void addMemberByUsernameNeverCreatesInExplicitExistingAccountMode() {
        when(authService.findByUsername("missing-user")).thenReturn(null);

        MateClawException ex = assertThrows(MateClawException.class, () -> service.addMemberByUsername(
                1L, "missing-user", "must-not-create", null, "member", false, false));

        assertEquals("err.workspace.user_not_found", ex.getMsgKey());
        verify(authService, never()).createUser(any());
        verify(memberMapper, never()).insert(any(WorkspaceMemberEntity.class));
    }
}
