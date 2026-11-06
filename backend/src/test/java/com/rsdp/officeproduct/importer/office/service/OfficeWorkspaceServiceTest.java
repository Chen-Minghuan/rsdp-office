package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.auth.AuthService;
import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.mapper.OfficeUserMapper;
import com.rsdp.officeproduct.importer.office.dto.CreateWorkspaceRequest;
import com.rsdp.officeproduct.importer.office.dto.CreateWorkspaceUserRequest;
import com.rsdp.officeproduct.importer.office.entity.OfficeAuditLog;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import com.rsdp.officeproduct.importer.office.entity.OfficeWorkspace;
import com.rsdp.officeproduct.importer.office.mapper.OfficeAuditLogMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignProjectMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceMapper;
import com.rsdp.officeproduct.importer.util.CurrentUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OfficeWorkspaceServiceTest {
    private final Map<String, OfficeUser> users = new LinkedHashMap<>();
    private final Map<String, OfficeWorkspace> workspaces = new LinkedHashMap<>();
    private final Map<String, OfficeDesignProject> projects = new LinkedHashMap<>();
    private final List<OfficeAuditLog> auditLogs = new ArrayList<>();
    private AuthService authService;
    private OfficeWorkspaceService service;

    @BeforeEach void setup() {
        users.clear();
        workspaces.clear();
        projects.clear();
        auditLogs.clear();
        authService = mock(AuthService.class);
        OfficeUserMapper userMapper = mock(OfficeUserMapper.class);
        OfficeWorkspaceMapper workspaceMapper = mock(OfficeWorkspaceMapper.class);
        OfficeDesignProjectMapper projectMapper = mock(OfficeDesignProjectMapper.class);
        OfficeAuditLogMapper auditLogMapper = mock(OfficeAuditLogMapper.class);

        when(userMapper.insert(any(OfficeUser.class))).thenAnswer(call -> {
            OfficeUser user = call.getArgument(0);
            boolean duplicate = users.values().stream().anyMatch(existing -> existing.getUsername().equals(user.getUsername()));
            if (duplicate) {
                throw new DuplicateKeyException("duplicate username");
            }
            users.put(user.getUserId(), user);
            return 1;
        });
        when(userMapper.updateById(any(OfficeUser.class))).thenAnswer(call -> {
            OfficeUser user = call.getArgument(0);
            users.put(user.getUserId(), user);
            return 1;
        });
        when(userMapper.selectById(anyString())).thenAnswer(call -> users.get(call.getArgument(0)));
        when(userMapper.selectOne(any())).thenAnswer(call -> {
            List<String> params = params(call.getArgument(0));
            return users.values().stream().filter(user -> params.contains(user.getUsername())).findFirst().orElse(null);
        });
        when(userMapper.selectBatchIds(any(Collection.class))).thenAnswer(call -> users.values().stream()
            .filter(user -> ((Collection<?>) call.getArgument(0)).contains(user.getUserId())).toList());
        when(userMapper.selectList(any())).thenAnswer(call -> {
            List<String> params = params(call.getArgument(0));
            return users.values().stream()
                .filter(user -> params.isEmpty() || params.contains(user.getWorkspaceId())).toList();
        });
        when(userMapper.selectCount(any())).thenAnswer(call -> {
            List<String> params = params(call.getArgument(0));
            return users.values().stream().filter(user -> params.contains(user.getWorkspaceId())).count();
        });
        when(workspaceMapper.insert(any(OfficeWorkspace.class))).thenAnswer(call -> {
            OfficeWorkspace workspace = call.getArgument(0);
            workspaces.put(workspace.getWorkspaceId(), workspace);
            return 1;
        });
        when(workspaceMapper.selectById(anyString())).thenAnswer(call -> workspaces.get(call.getArgument(0)));
        when(workspaceMapper.selectOne(any())).thenAnswer(call -> {
            List<String> params = params(call.getArgument(0));
            return workspaces.values().stream().filter(workspace -> params.contains(workspace.getOwnerUserId()))
                .findFirst().orElse(null);
        });
        when(workspaceMapper.selectList(any())).thenAnswer(call -> new ArrayList<>(workspaces.values()));
        when(projectMapper.selectCount(any())).thenAnswer(call -> {
            List<String> params = params(call.getArgument(0));
            return projects.values().stream().filter(project -> params.contains(project.getWorkspaceId())).count();
        });
        when(auditLogMapper.insert(any(OfficeAuditLog.class))).thenAnswer(call -> {
            auditLogs.add(call.getArgument(0));
            return 1;
        });

        var currentUserService = new CurrentUserService(authService, userMapper);
        service = new OfficeWorkspaceService(workspaceMapper, userMapper, projectMapper, currentUserService,
            new OfficeAuditService(auditLogMapper, currentUserService));
    }

    @AfterEach void tearDown() {
        CurrentUserContext.clear();
    }

    private OfficeUser addUser(String username, String role, String workspaceId) {
        OfficeUser user = new OfficeUser();
        user.setUserId("USR-" + username);
        user.setUsername(username);
        user.setDisplayName(username + " 显示名");
        user.setRole(role);
        user.setStatus("active");
        user.setWorkspaceId(workspaceId);
        user.setCreatedAt(LocalDateTime.now());
        users.put(user.getUserId(), user);
        return user;
    }

    private void loginAs(String username) {
        when(authService.isEnabled()).thenReturn(true);
        CurrentUserContext.set(username);
    }

    private CreateWorkspaceRequest workspaceRequest(String name, String ownerUsername) {
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName(name);
        request.setOwnerUsername(ownerUsername);
        request.setOwnerDisplayName("经销商主号 " + ownerUsername);
        request.setOwnerPassword("secret1");
        return request;
    }

    private CreateWorkspaceUserRequest accountRequest(String username, String source) {
        CreateWorkspaceUserRequest request = new CreateWorkspaceUserRequest();
        request.setUsername(username);
        request.setDisplayName("员工 " + username);
        request.setPassword("secret1");
        request.setSource(source);
        return request;
    }

    @Test void adminCreatesWorkspaceAndDealerAccountAtomically() {
        var dto = service.adminCreateWorkspace(workspaceRequest("华东经销商", "dealer-east"));
        assertTrue(dto.getWorkspaceId().startsWith("OWKS-"));
        assertEquals("华东经销商", dto.getName());
        assertEquals("dealer-east", dto.getOwnerUsername());
        assertEquals(1, dto.getUserCount());
        assertEquals(0, dto.getProjectCount());

        OfficeUser owner = users.get(dto.getOwnerUserId());
        assertEquals("DEALER", owner.getRole());
        assertEquals(dto.getWorkspaceId(), owner.getWorkspaceId());
        assertEquals(OfficeWorkspaceService.SOURCE_DEALER_STAFF, owner.getSource());
        assertNotEquals("secret1", owner.getPasswordHash());

        assertEquals(1, auditLogs.size());
        assertEquals("WORKSPACE_CREATED", auditLogs.get(0).getAction());
        assertEquals(dto.getWorkspaceId(), auditLogs.get(0).getTargetId());

        var listed = service.adminListWorkspaces();
        assertEquals(1, listed.size());
        assertEquals(dto.getWorkspaceId(), listed.get(0).getWorkspaceId());
    }

    @Test void adminCreateWorkspaceValidatesInput() {
        assertEquals("工作区名称不能为空", assertThrows(BusinessException.class,
            () -> service.adminCreateWorkspace(workspaceRequest(" ", "dealer1"))).getMessage());
        assertEquals("用户名需为 2-32 位字母、数字或 _ . -", assertThrows(BusinessException.class,
            () -> service.adminCreateWorkspace(workspaceRequest("经销商", "坏 名"))).getMessage());
        var shortPassword = workspaceRequest("经销商", "dealer1");
        shortPassword.setOwnerPassword("123");
        assertEquals("初始密码至少 6 位", assertThrows(BusinessException.class,
            () -> service.adminCreateWorkspace(shortPassword)).getMessage());

        addUser("dealer1", "DEALER", null);
        assertEquals("用户名已存在", assertThrows(BusinessException.class,
            () -> service.adminCreateWorkspace(workspaceRequest("经销商", "dealer1"))).getMessage());
        assertTrue(workspaces.isEmpty());
        assertTrue(auditLogs.isEmpty());
    }

    @Test void adminEndpointsRequireAdminWhenEnforced() {
        addUser("root", "ADMIN", null);
        addUser("bob", "USER", null);
        loginAs("bob");
        assertEquals("只有系统管理员可以管理用户", assertThrows(AccessDeniedException.class,
            () -> service.adminListWorkspaces()).getMessage());
        assertThrows(AccessDeniedException.class,
            () -> service.adminCreateWorkspace(workspaceRequest("经销商", "dealer1")));
        loginAs("root");
        assertDoesNotThrow(() -> service.adminListWorkspaces());
    }

    @Test void dealerCreatesWorkspaceAccountsWithSource() {
        var workspace = service.adminCreateWorkspace(workspaceRequest("华东经销商", "dealer-east"));
        loginAs("dealer-east");

        var staff = service.createWorkspaceUser(accountRequest("alice", null));
        assertEquals("USER", staff.getRole());
        assertEquals(OfficeWorkspaceService.SOURCE_DEALER_STAFF, staff.getSource());
        assertEquals(workspace.getWorkspaceId(), users.get(staff.getUserId()).getWorkspaceId());

        var sales = service.createWorkspaceUser(accountRequest("bob", "factory_sales"));
        assertEquals(OfficeWorkspaceService.SOURCE_FACTORY_SALES, sales.getSource());

        assertEquals("账号来源只能是经销商员工或厂家业务员", assertThrows(BusinessException.class,
            () -> service.createWorkspaceUser(accountRequest("carol", "ADMIN"))).getMessage());
        assertEquals("用户名已存在", assertThrows(BusinessException.class,
            () -> service.createWorkspaceUser(accountRequest("alice", null))).getMessage());

        // 其他工作区账号不出现在本工作区列表
        addUser("outsider", "USER", "OWKS-OTHER");
        var list = service.listWorkspaceUsers();
        assertEquals(3, list.size()); // 主号 + alice + bob
        assertTrue(list.stream().noneMatch(user -> user.getUsername().equals("outsider")));

        assertEquals(2, auditLogs.stream().filter(log -> "ACCOUNT_CREATED".equals(log.getAction())).count());
        assertEquals(workspace.getOwnerUserId(), auditLogs.get(auditLogs.size() - 1).getActorUserId());
    }

    @Test void dealerEndpointsRejectNonDealerAndDevMode() {
        var workspace = service.adminCreateWorkspace(workspaceRequest("华东经销商", "dealer-east"));
        addUser("root", "ADMIN", null);
        addUser("plain", "USER", workspace.getWorkspaceId());

        loginAs("plain");
        assertEquals("只有经销商主号可以管理工作区账号", assertThrows(AccessDeniedException.class,
            () -> service.listWorkspaceUsers()).getMessage());
        assertThrows(AccessDeniedException.class,
            () -> service.createWorkspaceUser(accountRequest("alice", null)));
        loginAs("root");
        assertThrows(AccessDeniedException.class, () -> service.myWorkspace());

        // 开发模式（认证关闭）无法解析"我的工作区"，明确报错而非猜测
        when(authService.isEnabled()).thenReturn(false);
        CurrentUserContext.clear();
        assertEquals("工作区接口需要开启认证并登录经销商主号后使用", assertThrows(BusinessException.class,
            () -> service.listWorkspaceUsers()).getMessage());
    }

    private List<String> params(QueryWrapper<?> query) {
        query.getSqlSegment();
        return query.getParamNameValuePairs().values().stream().flatMap(value -> {
            if (value instanceof Collection<?> values) return values.stream();
            return Stream.of(value);
        }).filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
