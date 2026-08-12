package vip.mate.workspace.core.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;
import vip.mate.exception.MateClawException;
import vip.mate.workspace.core.model.WorkspaceMemberEntity;
import vip.mate.workspace.core.service.WorkspaceService;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorkspaceControllerMembersAuthTest {

    @Test
    void listMembersRequiresViewerPermissionForNonGlobalAdmin() throws Exception {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AuthService authService = mock(AuthService.class);
        WorkspaceController controller = new WorkspaceController(workspaceService, authService);
        UserEntity user = new UserEntity();
        user.setId(42L);
        user.setUsername("alice");
        user.setRole("user");
        when(authService.findByUsername("alice")).thenReturn(user);
        when(workspaceService.listMembers(7L)).thenReturn(List.of());

        invokeListMembers(controller, 7L, new TestingAuthenticationToken("alice", "pw"));

        verify(workspaceService).requirePermission(7L, 42L, "viewer");
        verify(workspaceService).listMembers(7L);
    }

    @Test
    void listMembersLetsGlobalAdminBypassWorkspaceMembership() throws Exception {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AuthService authService = mock(AuthService.class);
        WorkspaceController controller = new WorkspaceController(workspaceService, authService);
        UserEntity user = new UserEntity();
        user.setId(1L);
        user.setUsername("admin");
        user.setRole("admin");
        when(authService.findByUsername("admin")).thenReturn(user);
        when(workspaceService.listMembers(7L)).thenReturn(List.of());

        invokeListMembers(controller, 7L, new TestingAuthenticationToken("admin", "pw"));

        verify(workspaceService, never()).requirePermission(anyLong(), anyLong(), anyString());
        verify(workspaceService).listMembers(7L);
    }

    @Test
    void listMembersMarksDisabledAndMissingAccountsInactive() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AuthService authService = mock(AuthService.class);
        WorkspaceController controller = new WorkspaceController(workspaceService, authService);
        UserEntity admin = user(1L, "admin", true);
        when(authService.findByUsername("admin")).thenReturn(admin);

        WorkspaceMemberEntity active = member(11L, "member");
        WorkspaceMemberEntity disabled = member(12L, "admin");
        WorkspaceMemberEntity missing = member(13L, "admin");
        when(workspaceService.listMembers(7L)).thenReturn(List.of(active, disabled, missing));
        when(authService.findById(11L)).thenReturn(user(11L, "active", true));
        when(authService.findById(12L)).thenReturn(user(12L, "disabled", false));
        when(authService.findById(13L)).thenReturn(null);

        List<WorkspaceMemberEntity> members = controller.listMembers(
                7L, new TestingAuthenticationToken("admin", "pw")).getData();

        assertThat(members).extracting(WorkspaceMemberEntity::getActive)
                .containsExactly(true, false, false);
    }

    @Test
    void addExistingMemberDelegatesWithoutAccountCreationPermission() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AuthService authService = mock(AuthService.class);
        WorkspaceController controller = new WorkspaceController(workspaceService, authService);
        when(authService.findByUsername("admin")).thenReturn(user(1L, "admin", true));
        WorkspaceMemberEntity expected = member(88L, "member");
        when(workspaceService.addMemberByUsername(
                7L, "existing-user", null, null, "member", false, false)).thenReturn(expected);
        Map<String, Object> request = new HashMap<>();
        request.put("username", "existing-user");
        request.put("createUser", false);
        request.put("role", "member");

        WorkspaceMemberEntity result = controller.addMember(
                7L, request, new TestingAuthenticationToken("admin", "pw")).getData();

        assertThat(result).isSameAs(expected);
        verify(workspaceService).addMemberByUsername(
                7L, "existing-user", null, null, "member", false, false);
        verify(authService, never()).createUser(any());
    }

    @Test
    void addNewMemberDelegatesGlobalAdminAuthorityExplicitly() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AuthService authService = mock(AuthService.class);
        WorkspaceController controller = new WorkspaceController(workspaceService, authService);
        UserEntity globalAdmin = user(1L, "admin", true);
        globalAdmin.setRole("admin");
        when(authService.findByUsername("admin")).thenReturn(globalAdmin);
        WorkspaceMemberEntity expected = member(88L, "admin");
        when(workspaceService.addMemberByUsername(
                7L, "new-reviewer", "temporary-password", "三线复核人", "admin", true, true))
                .thenReturn(expected);
        Map<String, Object> request = new HashMap<>();
        request.put("username", "new-reviewer");
        request.put("password", "temporary-password");
        request.put("nickname", "三线复核人");
        request.put("createUser", true);
        request.put("role", "admin");

        WorkspaceMemberEntity result = controller.addMember(
                7L, request, new TestingAuthenticationToken("admin", "pw")).getData();

        assertThat(result).isSameAs(expected);
        verify(workspaceService, never()).requirePermission(anyLong(), anyLong(), anyString());
        verify(workspaceService).addMemberByUsername(
                7L, "new-reviewer", "temporary-password", "三线复核人", "admin", true, true);
    }

    @Test
    void addNewMemberDoesNotGrantAccountCreationAuthorityToWorkspaceAdmin() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        AuthService authService = mock(AuthService.class);
        WorkspaceController controller = new WorkspaceController(workspaceService, authService);
        when(authService.findByUsername("workspace-admin"))
                .thenReturn(user(9L, "workspace-admin", true));
        when(workspaceService.addMemberByUsername(
                7L, "new-user", "password", null, "member", true, false))
                .thenThrow(new MateClawException(
                        "err.workspace.insufficient_permission", 403,
                        "Only a global administrator can create a MateClaw account"));
        Map<String, Object> request = new HashMap<>();
        request.put("username", "new-user");
        request.put("password", "password");
        request.put("createUser", true);
        request.put("role", "member");

        MateClawException error = assertThrows(
                MateClawException.class,
                () -> controller.addMember(
                        7L, request, new TestingAuthenticationToken("workspace-admin", "pw")));

        assertThat(error.getCode()).isEqualTo(403);
        verify(workspaceService).requirePermission(7L, 9L, "admin");
        verify(workspaceService).addMemberByUsername(
                7L, "new-user", "password", null, "member", true, false);
    }

    private WorkspaceMemberEntity member(long userId, String role) {
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspaceId(7L);
        member.setUserId(userId);
        member.setRole(role);
        return member;
    }

    private UserEntity user(long userId, String username, boolean enabled) {
        UserEntity user = new UserEntity();
        user.setId(userId);
        user.setUsername(username);
        user.setEnabled(enabled);
        user.setRole("user");
        return user;
    }

    private void invokeListMembers(WorkspaceController controller, Long workspaceId,
                                   Authentication auth) throws Exception {
        Method method = WorkspaceController.class.getMethod("listMembers", Long.class, Authentication.class);
        assertNotNull(method);
        method.invoke(controller, workspaceId, auth);
    }
}
