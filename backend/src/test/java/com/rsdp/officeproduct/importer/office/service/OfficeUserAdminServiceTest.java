package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.auth.AuthService;
import com.rsdp.officeproduct.importer.auth.PasswordHasher;
import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.entity.OfficeUserSession;
import com.rsdp.officeproduct.importer.mapper.OfficeUserMapper;
import com.rsdp.officeproduct.importer.mapper.OfficeUserSessionMapper;
import com.rsdp.officeproduct.importer.office.entity.OfficeProjectMember;
import com.rsdp.officeproduct.importer.office.entity.OfficeWorkspace;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProjectMemberMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceMapper;
import com.rsdp.officeproduct.importer.office.dto.AdminUserDto;
import com.rsdp.officeproduct.importer.office.dto.CreateOfficeUserRequest;
import com.rsdp.officeproduct.importer.util.CurrentUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OfficeUserAdminServiceTest {
    private final Map<String, OfficeUser> users = new LinkedHashMap<>();
    private final List<OfficeUserSession> sessions = new ArrayList<>();
    private final List<OfficeProjectMember> members = new ArrayList<>();
    private final List<OfficeWorkspace> workspaces = new ArrayList<>();
    private AuthService authService;
    private OfficeUserAdminService service;

    @BeforeEach void setup() {
        users.clear();
        sessions.clear();
        members.clear();
        workspaces.clear();
        authService = mock(AuthService.class);
        OfficeUserMapper userMapper = mock(OfficeUserMapper.class);
        OfficeUserSessionMapper sessionMapper = mock(OfficeUserSessionMapper.class);
        OfficeProjectMemberMapper memberMapper = mock(OfficeProjectMemberMapper.class);
        OfficeWorkspaceMapper workspaceMapper = mock(OfficeWorkspaceMapper.class);
        when(workspaceMapper.selectCount(any())).thenAnswer(call -> {
            List<String> params = params(call.getArgument(0));
            return workspaces.stream().filter(workspace -> params.contains(workspace.getOwnerUserId())).count();
        });
        when(memberMapper.selectCount(any())).thenAnswer(call -> {
            List<String> params = params(call.getArgument(0));
            return members.stream().filter(member -> params.contains(member.getUserId())
                && (!params.contains(OfficeProjectMember.ROLE_LEADER) || OfficeProjectMember.ROLE_LEADER.equals(member.getRole()))).count();
        });
        when(memberMapper.delete(any())).thenAnswer(call -> {
            List<String> params = params(call.getArgument(0));
            int removed = 0;
            for (Iterator<OfficeProjectMember> it = members.iterator(); it.hasNext();) {
                if (params.contains(it.next().getUserId())) { it.remove(); removed++; }
            }
            return removed;
        });
        when(userMapper.deleteById(anyString())).thenAnswer(call -> users.remove(call.getArgument(0)) != null ? 1 : 0);
        when(userMapper.selectById(anyString())).thenAnswer(call -> users.get(call.getArgument(0)));
        when(userMapper.selectOne(any())).thenAnswer(call -> {
            List<String> params = params(call.getArgument(0));
            return users.values().stream().filter(user -> params.contains(user.getUsername())).findFirst().orElse(null);
        });
        when(userMapper.selectList(any())).thenAnswer(call -> {
            List<String> params = params(call.getArgument(0));
            return users.values().stream()
                .filter(user -> !params.contains("ADMIN") || "ADMIN".equals(user.getRole()))
                .filter(user -> !params.contains("active") || "active".equals(user.getStatus()))
                .toList();
        });
        when(userMapper.insert(any(OfficeUser.class))).thenAnswer(call -> {
            OfficeUser user = call.getArgument(0);
            users.put(user.getUserId(), user);
            return 1;
        });
        when(userMapper.updateById(any(OfficeUser.class))).thenAnswer(call -> {
            OfficeUser user = call.getArgument(0);
            users.put(user.getUserId(), user);
            return 1;
        });
        when(sessionMapper.delete(any())).thenAnswer(call -> {
            List<String> params = params(call.getArgument(0));
            int removed = 0;
            for (Iterator<OfficeUserSession> it = sessions.iterator(); it.hasNext();) {
                if (params.contains(it.next().getUserId())) { it.remove(); removed++; }
            }
            return removed;
        });
        service = new OfficeUserAdminService(userMapper, sessionMapper, memberMapper, workspaceMapper,
            new CurrentUserService(authService, userMapper));
    }

    @AfterEach void tearDown() {
        CurrentUserContext.clear();
    }

    private OfficeUser addUser(String username, String role, String status) {
        OfficeUser user = new OfficeUser();
        user.setUserId("USR-" + username);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setRole(role);
        user.setStatus(status);
        user.setCreatedAt(LocalDateTime.now());
        users.put(user.getUserId(), user);
        return user;
    }

    private void loginAs(String username) {
        when(authService.isEnabled()).thenReturn(true);
        CurrentUserContext.set(username);
    }

    private void addSession(String userId, String sessionId) {
        OfficeUserSession session = new OfficeUserSession();
        session.setSessionId(sessionId);
        session.setUserId(userId);
        sessions.add(session);
    }

    private void addMember(String projectId, String userId, String role) {
        OfficeProjectMember member = new OfficeProjectMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setRole(role);
        members.add(member);
    }

    private CreateOfficeUserRequest createRequest(String username, String displayName, String password, String role) {
        CreateOfficeUserRequest request = new CreateOfficeUserRequest();
        request.setUsername(username);
        request.setDisplayName(displayName);
        request.setPassword(password);
        request.setRole(role);
        return request;
    }

    @Test void nonAdminCannotManageUsers() {
        addUser("root", "ADMIN", "active");
        addUser("bob", "USER", "active");
        loginAs("bob");
        assertEquals("只有系统管理员可以管理用户", assertThrows(AccessDeniedException.class,
            () -> service.listUsers()).getMessage());
        assertThrows(AccessDeniedException.class,
            () -> service.createUser(createRequest("carol", "Carol", "secret1", null)));

        loginAs("root");
        assertEquals(2, service.listUsers().size());
    }

    @Test void devModeSkipsAdminCheck() {
        when(authService.isEnabled()).thenReturn(false);
        AdminUserDto created = service.createUser(createRequest("carol", "Carol", "secret1", null));
        assertEquals("USER", created.getRole());
        assertEquals(1, service.listUsers().size());
    }

    @Test void createUserValidatesInputAndStoresHashOnly() {
        addUser("root", "ADMIN", "active");
        addUser("bob", "USER", "active");
        loginAs("root");
        assertEquals("用户名已存在", assertThrows(BusinessException.class,
            () -> service.createUser(createRequest("bob", "B", "secret1", null))).getMessage());
        assertEquals("用户名需为 2-32 位字母、数字或 _ . -", assertThrows(BusinessException.class,
            () -> service.createUser(createRequest("坏 名", "B", "secret1", null))).getMessage());
        assertEquals("显示名称不能为空", assertThrows(BusinessException.class,
            () -> service.createUser(createRequest("carol", " ", "secret1", null))).getMessage());
        assertEquals("初始密码至少 6 位", assertThrows(BusinessException.class,
            () -> service.createUser(createRequest("carol", "Carol", "123", null))).getMessage());
        // 系统管理员唯一：本接口只能创建设计师（普通用户）；经销商主号走工作区创建
        assertEquals("只能创建设计师账号；经销商主号请通过经销商工作区创建", assertThrows(BusinessException.class,
            () -> service.createUser(createRequest("carol", "Carol", "secret1", "SUPER"))).getMessage());
        assertEquals("只能创建设计师账号；经销商主号请通过经销商工作区创建", assertThrows(BusinessException.class,
            () -> service.createUser(createRequest("carol", "Carol", "secret1", "admin"))).getMessage());
        assertEquals("只能创建设计师账号；经销商主号请通过经销商工作区创建", assertThrows(BusinessException.class,
            () -> service.createUser(createRequest("carol", "Carol", "secret1", "DEALER"))).getMessage());

        AdminUserDto created = service.createUser(createRequest("carol", "Carol", "secret1", "user"));
        assertEquals("USER", created.getRole());
        assertEquals("active", created.getStatus());
        OfficeUser stored = users.get(created.getUserId());
        assertNotEquals("secret1", stored.getPasswordHash());
        assertTrue(PasswordHasher.matches("secret1", stored.getPasswordHash()));
    }

    @Test void disableRulesAndSessionInvalidation() {
        OfficeUser root = addUser("root", "ADMIN", "active");
        OfficeUser bob = addUser("bob", "USER", "active");
        loginAs("root");
        // 系统管理员账号（含自己）不可被停用
        assertEquals("不能操作系统管理员账号", assertThrows(BusinessException.class,
            () -> service.updateStatus(root.getUserId(), "disabled")).getMessage());
        assertEquals("状态只能是 active 或 disabled", assertThrows(BusinessException.class,
            () -> service.updateStatus(bob.getUserId(), "banned")).getMessage());

        addSession(bob.getUserId(), "S1");
        AdminUserDto disabled = service.updateStatus(bob.getUserId(), "disabled");
        assertEquals("disabled", disabled.getStatus());
        assertTrue(sessions.isEmpty());
        assertEquals("active", service.updateStatus(bob.getUserId(), "active").getStatus());

        // 开发模式（认证关闭）无操作人身份：目标角色规则仍生效
        when(authService.isEnabled()).thenReturn(false);
        CurrentUserContext.clear();
        assertEquals("不能操作系统管理员账号", assertThrows(BusinessException.class,
            () -> service.updateStatus(root.getUserId(), "disabled")).getMessage());
        assertEquals("disabled", service.updateStatus(bob.getUserId(), "disabled").getStatus());
    }

    @Test void resetPasswordChangesHashAndInvalidatesSessions() {
        OfficeUser root = addUser("root", "ADMIN", "active");
        OfficeUser bob = addUser("bob", "USER", "active");
        loginAs("root");
        assertEquals("新密码至少 6 位", assertThrows(BusinessException.class,
            () -> service.resetPassword(bob.getUserId(), "123")).getMessage());
        assertEquals("不能操作系统管理员账号", assertThrows(BusinessException.class,
            () -> service.resetPassword(root.getUserId(), "newpass9")).getMessage());

        addSession(bob.getUserId(), "S1");
        service.resetPassword(bob.getUserId(), "newpass9");
        assertTrue(PasswordHasher.matches("newpass9", users.get(bob.getUserId()).getPasswordHash()));
        assertTrue(sessions.isEmpty());
        assertEquals("用户不存在", assertThrows(BusinessException.class,
            () -> service.resetPassword("USR-none", "newpass9")).getMessage());
    }

    @Test void deleteUserRemovesCollaboratorMembershipsAndSessions() {
        addUser("root", "ADMIN", "active");
        OfficeUser bob = addUser("bob", "USER", "active");
        loginAs("root");
        addMember("P1", bob.getUserId(), OfficeProjectMember.ROLE_COLLABORATOR);
        addMember("P2", bob.getUserId(), OfficeProjectMember.ROLE_COLLABORATOR);
        addSession(bob.getUserId(), "S1");

        service.deleteUser(bob.getUserId());
        assertNull(users.get(bob.getUserId()));
        assertTrue(members.isEmpty());
        assertTrue(sessions.isEmpty());
        assertEquals("用户不存在", assertThrows(BusinessException.class,
            () -> service.deleteUser(bob.getUserId())).getMessage());
    }

    @Test void deleteUserBlocksAdminLeaderAndWorkspaceOwner() {
        OfficeUser root = addUser("root", "ADMIN", "active");
        OfficeUser bob = addUser("bob", "USER", "active");
        loginAs("root");
        // 系统管理员唯一：任何 ADMIN 账号（含自己）不可删除
        assertEquals("不能操作系统管理员账号", assertThrows(BusinessException.class,
            () -> service.deleteUser(root.getUserId())).getMessage());

        addMember("P1", bob.getUserId(), OfficeProjectMember.ROLE_LEADER);
        assertEquals("该用户仍是 1 个项目的负责人，请先在项目中更换负责人", assertThrows(BusinessException.class,
            () -> service.deleteUser(bob.getUserId())).getMessage());
        assertNotNull(users.get(bob.getUserId()));

        // 开发模式无操作人身份：目标角色规则仍生效
        when(authService.isEnabled()).thenReturn(false);
        CurrentUserContext.clear();
        assertEquals("不能操作系统管理员账号", assertThrows(BusinessException.class,
            () -> service.deleteUser(root.getUserId())).getMessage());
    }

    @Test void deleteUserBlocksDealerWhoOwnsWorkspace() {
        addUser("root", "ADMIN", "active");
        OfficeUser dealer = addUser("dealer1", "DEALER", "active");
        loginAs("root");
        OfficeWorkspace workspace = new OfficeWorkspace();
        workspace.setWorkspaceId("OWKS-1");
        workspace.setOwnerUserId(dealer.getUserId());
        workspaces.add(workspace);
        assertEquals("该用户仍是经销商主号，请先停用或移交其工作区后再删除", assertThrows(BusinessException.class,
            () -> service.deleteUser(dealer.getUserId())).getMessage());
        assertNotNull(users.get(dealer.getUserId()));
        // 停用经销商主号不拦截：工作区/项目/账号保留，会话失效沿用既有逻辑
        addSession(dealer.getUserId(), "S1");
        assertEquals("disabled", service.updateStatus(dealer.getUserId(), "disabled").getStatus());
        assertTrue(sessions.isEmpty());
    }

    private List<String> params(QueryWrapper<?> query) {
        query.getSqlSegment();
        return query.getParamNameValuePairs().values().stream().flatMap(value -> {
            if (value instanceof Collection<?> values) return values.stream();
            return Stream.of(value);
        }).filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
