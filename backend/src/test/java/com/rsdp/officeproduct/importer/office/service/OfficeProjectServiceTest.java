package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.mapper.OfficeUserMapper;
import com.rsdp.officeproduct.importer.service.RsdpCatalogGateway;
import com.rsdp.officeproduct.importer.office.dto.CreateOfficeFloorRequest;
import com.rsdp.officeproduct.importer.office.dto.CreateOfficeProjectRequest;
import com.rsdp.officeproduct.importer.office.dto.OfficeProjectDto;
import com.rsdp.officeproduct.importer.office.dto.ProjectReferenceImage;
import com.rsdp.officeproduct.importer.office.dto.UpdateOfficeProjectRequest;
import com.rsdp.officeproduct.importer.office.dto.UpdateOfficeRequirementsRequest;
import com.rsdp.officeproduct.importer.office.dto.UpdateProjectMembersRequest;
import com.rsdp.officeproduct.importer.office.dto.UpdateProjectReferencesRequest;
import com.rsdp.officeproduct.importer.office.entity.OfficeCart;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignFloor;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import com.rsdp.officeproduct.importer.office.entity.OfficeAuditLog;
import com.rsdp.officeproduct.importer.office.entity.OfficeProjectMember;
import com.rsdp.officeproduct.importer.office.mapper.OfficeAuditLogMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCartMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignFloorMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignProjectMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProjectMemberMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OfficeProjectServiceTest {
    private final Map<String, OfficeDesignProject> projects = new LinkedHashMap<>();
    private final Map<String, OfficeDesignFloor> floors = new LinkedHashMap<>();
    private final Map<String, OfficeUser> users = new LinkedHashMap<>();
    private final List<OfficeProjectMember> members = new ArrayList<>();
    private final List<OfficeAuditLog> auditLogs = new ArrayList<>();
    private final List<OfficeCart> cartLines = new ArrayList<>();
    private OfficeDesignProjectMapper projectMapper;
    private OfficeDesignFloorMapper floorMapper;
    private OfficeProjectMemberMapper memberMapper;
    private OfficeUserMapper userMapper;
    private OfficeProjectAccessService access;
    private OfficeAuditService auditService;
    private OfficeCartMapper cartMapper;
    private RsdpCatalogGateway catalogGateway;
    private SharedCatalogService sharedCatalogService;
    private OfficeProjectService service;

    @BeforeEach void setup() {
        projects.clear();
        floors.clear();
        users.clear();
        members.clear();
        auditLogs.clear();
        cartLines.clear();
        projectMapper = mock(OfficeDesignProjectMapper.class);
        floorMapper = mock(OfficeDesignFloorMapper.class);
        memberMapper = mock(OfficeProjectMemberMapper.class);
        userMapper = mock(OfficeUserMapper.class);
        var auditLogMapper = mock(OfficeAuditLogMapper.class);
        access = mock(OfficeProjectAccessService.class);
        cartMapper = mock(OfficeCartMapper.class);
        catalogGateway = mock(RsdpCatalogGateway.class);
        sharedCatalogService = mock(SharedCatalogService.class);
        when(access.enforcementEnabled()).thenReturn(false);
        when(access.accessibleProjectIds()).thenReturn(null);
        when(auditLogMapper.insert(any(OfficeAuditLog.class))).thenAnswer(call -> { auditLogs.add(call.getArgument(0)); return 1; });
        var currentUserService = mock(CurrentUserService.class);
        when(currentUserService.authEnabled()).thenReturn(false);
        when(projectMapper.insert(any(OfficeDesignProject.class))).thenAnswer(call -> { projects.put(call.getArgument(0, OfficeDesignProject.class).getProjectId(), call.getArgument(0)); return 1; });
        when(projectMapper.updateById(any(OfficeDesignProject.class))).thenAnswer(call -> { projects.put(call.getArgument(0, OfficeDesignProject.class).getProjectId(), call.getArgument(0)); return 1; });
        when(projectMapper.updateRequirements(anyString(), anyString(), any())).thenAnswer(call -> {
            OfficeDesignProject project = projects.get(call.getArgument(0));
            if (project == null) return 0;
            project.setCustomerRequirements(call.getArgument(1));
            project.setRequirementsRevision(project.getRequirementsRevision() + 1);
            project.setUpdatedAt(call.getArgument(2));
            return 1;
        });
        when(projectMapper.selectById(anyString())).thenAnswer(call -> projects.get(call.getArgument(0)));
        when(projectMapper.updateStatus(anyString(), anyString(), any())).thenAnswer(call -> {
            OfficeDesignProject project = projects.get(call.getArgument(0));
            if (project == null) return 0;
            project.setStatus(call.getArgument(1));
            project.setUpdatedAt(call.getArgument(2));
            return 1;
        });
        when(projectMapper.selectList(any())).thenAnswer(call -> new ArrayList<>(projects.values()));
        when(floorMapper.insert(any(OfficeDesignFloor.class))).thenAnswer(call -> { floors.put(call.getArgument(0, OfficeDesignFloor.class).getFloorId(), call.getArgument(0)); return 1; });
        when(floorMapper.selectById(anyString())).thenAnswer(call -> floors.get(call.getArgument(0)));
        when(floorMapper.selectList(any())).thenAnswer(call -> floors.values().stream()
            .filter(floor -> scopeIds(call.getArgument(0)).contains(floor.getProjectId())).toList());
        when(floorMapper.nextSortOrder(anyString())).thenAnswer(call -> floors.values().stream()
            .filter(floor -> floor.getProjectId().equals(call.getArgument(0)))
            .mapToInt(OfficeDesignFloor::getSortOrder).max().orElse(-1) + 1);
        when(memberMapper.insert(any(OfficeProjectMember.class))).thenAnswer(call -> { members.add(call.getArgument(0)); return 1; });
        when(memberMapper.selectList(any())).thenAnswer(call -> members.stream()
            .filter(member -> scopeIds(call.getArgument(0)).contains(member.getProjectId())).toList());
        when(memberMapper.delete(any())).thenAnswer(call -> { members.removeIf(member -> scopeIds(call.getArgument(0)).contains(member.getProjectId())); return 1; });
        when(cartMapper.selectList(any())).thenAnswer(call -> cartLines.stream()
            .filter(cart -> scopeIds(call.getArgument(0)).contains(cart.getProjectId())).toList());
        when(sharedCatalogService.resolveViewMode()).thenReturn("full");
        when(userMapper.selectById(anyString())).thenAnswer(call -> users.get(call.getArgument(0)));
        when(userMapper.selectBatchIds(any(Collection.class))).thenAnswer(call -> users.values().stream()
            .filter(user -> ((Collection<?>) call.getArgument(0)).contains(user.getUserId())).toList());
        when(userMapper.selectList(any())).thenAnswer(call -> {
            List<String> params = scopeIds(call.getArgument(0));
            List<String> workspaceParams = params.stream()
                .filter(param -> !"active".equals(param) && !"disabled".equals(param)).toList();
            return users.values().stream()
                .filter(user -> !params.contains("active") || "active".equals(user.getStatus()))
                .filter(user -> workspaceParams.isEmpty() || workspaceParams.contains(user.getWorkspaceId()))
                .toList();
        });
        auditService = new OfficeAuditService(auditLogMapper, currentUserService);
        service = new OfficeProjectService(projectMapper, floorMapper, memberMapper, userMapper, access,
            auditService, new ObjectMapper(), cartMapper, catalogGateway, sharedCatalogService);
    }

    private OfficeUser activeUser(String userId, String username) {
        return activeUserIn(userId, username, null);
    }

    private OfficeUser activeUserIn(String userId, String username, String workspaceId) {
        var user = new OfficeUser();
        user.setUserId(userId);
        user.setUsername(username);
        user.setDisplayName(username + " 显示名");
        user.setStatus("active");
        user.setWorkspaceId(workspaceId);
        users.put(userId, user);
        return user;
    }

    @Test void createsProjectWithFloorsAndListsProjectTree() {
        var created = service.createProject(request("  总部办公  ", List.of(" 一层 ", "二层")));
        assertTrue(created.getProjectId().startsWith("OPRJ-"));
        assertEquals("总部办公", created.getName());
        assertEquals("需要开放办公区", created.getCustomerRequirements());
        assertEquals(1, created.getRequirementsRevision());
        assertEquals(2, created.getFloors().size());
        assertEquals("一层", created.getFloors().get(0).getName());
        assertTrue(created.getFloors().get(0).getFloorId().startsWith("OFLR-"));
        assertEquals(created.getProjectId(), created.getFloors().get(0).getProjectId());
        assertNotNull(created.getCreatedAt());
        assertNotNull(created.getFloors().get(0).getUpdatedAt());

        var tree = service.listProjects();
        assertEquals(1, tree.size());
        assertEquals(created.getProjectId(), tree.get(0).getProjectId());
        assertEquals(List.of(0, 1), tree.get(0).getFloors().stream().map(floor -> floor.getSortOrder()).toList());
    }

    @Test void projectStatusDefaultsToDesigningAndPersistsEverySupportedStage() {
        var created = service.createProject(request("阶段项目", List.of("一层")));
        assertEquals("DESIGNING", created.getStatus());
        for (String stage : List.of("DELIVERING", "COMPLETED", "DESIGNING")) {
            assertEquals(stage, service.updateStatus(created.getProjectId(), stage));
            assertEquals(stage, service.listProjects(false).getFirst().getStatus());
        }
        verify(access, times(3)).requireStatusManager(created.getProjectId());
    }

    @Test void invalidOrUnauthorizedProjectStatusCannotChangeSavedState() {
        var created = service.createProject(request("阶段项目", List.of("一层")));
        assertThrows(BusinessException.class, () -> service.updateStatus(created.getProjectId(), null));
        assertThrows(BusinessException.class, () -> service.updateStatus(created.getProjectId(), "UNKNOWN"));
        doThrow(new com.rsdp.officeproduct.importer.common.AccessDeniedException("无权修改"))
            .when(access).requireStatusManager(created.getProjectId());
        assertThrows(com.rsdp.officeproduct.importer.common.AccessDeniedException.class,
            () -> service.updateStatus(created.getProjectId(), "COMPLETED"));
        verify(projectMapper, never()).updateStatus(anyString(), anyString(), any());
        assertEquals("DESIGNING", projects.get(created.getProjectId()).getStatus());
    }

    @Test void listsProjectsWithoutReadingCartOrCatalogWhenBudgetIsExcluded() {
        var created = service.createProject(request("总部", List.of("一层")));
        cartLines.add(cart(created.getFloors().getFirst().getFloorId(), "V-1", 1));

        var projects = service.listProjects(false);

        assertEquals(created.getProjectId(), projects.getFirst().getProjectId());
        assertNull(projects.getFirst().getBudgetTotal());
        assertNull(projects.getFirst().getBudgetStatus());
        verifyNoInteractions(cartMapper, catalogGateway);
    }

    @Test void separateBudgetsUseOnlyProjectsTheCurrentAccountCanRead() {
        var visible = service.createProject(request("有权限", List.of("一层")));
        var hidden = service.createProject(request("无权限", List.of("一层")));
        cartLines.add(cart(visible.getFloors().getFirst().getFloorId(), "V-1", 2));
        cartLines.add(cart(hidden.getFloors().getFirst().getFloorId(), "V-1", 100));
        when(access.accessibleProjectIds()).thenReturn(java.util.Set.of(visible.getProjectId()));
        when(sharedCatalogService.resolveViewMode()).thenReturn("full");
        when(catalogGateway.retailPrices("full", java.util.Set.of("V-1")))
            .thenReturn(Map.of("V-1", new BigDecimal("100")));

        var budgets = service.listProjectBudgets();

        assertEquals(java.util.Set.of(visible.getProjectId()), budgets.keySet());
        assertEquals(new BigDecimal("200"), budgets.get(visible.getProjectId()).budgetTotal());
        assertEquals("COMPLETE", budgets.get(visible.getProjectId()).budgetStatus());

        when(access.accessibleProjectIds()).thenReturn(java.util.Set.of());
        assertTrue(service.listProjectBudgets().isEmpty());
        verify(catalogGateway, times(1)).retailPrices("full", java.util.Set.of("V-1"));
    }

    @Test void listsProjectBudgetAcrossFloorsAndMarksMissingPrices() {
        var first = service.createProject(request("总部", List.of("一层", "二层")));
        var second = service.createProject(request("分部", List.of("一层")));
        cartLines.add(cart(first.getFloors().get(0).getFloorId(), "V-1", 2));
        cartLines.add(cart(first.getFloors().get(1).getFloorId(), "V-2", 3));
        cartLines.add(cart(first.getFloors().get(1).getFloorId(), "MISSING", 1));
        cartLines.add(cart(second.getFloors().get(0).getFloorId(), "V-3", 4));
        when(catalogGateway.retailPrices("full", java.util.Set.of("V-1", "V-2", "V-3", "MISSING")))
            .thenReturn(Map.of("V-1", new BigDecimal("100"), "V-2", new BigDecimal("50"), "V-3", new BigDecimal("10")));

        var result = budgetService().listProjects();

        var headquarters = project(result, first.getProjectId());
        assertEquals(new BigDecimal("350"), budgetTotal(headquarters));
        assertEquals("PENDING_PRICING", budgetStatus(headquarters));
        var branch = project(result, second.getProjectId());
        assertEquals(new BigDecimal("40"), budgetTotal(branch));
        assertEquals("COMPLETE", budgetStatus(branch));
    }

    @Test void listsZeroBudgetForProjectsWithoutCartItems() {
        var created = service.createProject(request("空项目", List.of("一层")));

        var project = project(budgetService().listProjects(), created.getProjectId());

        assertEquals(BigDecimal.ZERO, budgetTotal(project));
        assertEquals("COMPLETE", budgetStatus(project));
        verifyNoInteractions(catalogGateway);
    }

    @Test void keepsProjectListAvailableWhenCatalogPricingFails() {
        var created = service.createProject(request("待价项目", List.of("一层")));
        cartLines.add(cart(created.getFloors().get(0).getFloorId(), "V-1", 1));
        when(catalogGateway.retailPrices("full", java.util.Set.of("V-1")))
            .thenThrow(new BusinessException("批量查价不可用"));

        var project = project(budgetService().listProjects(), created.getProjectId());

        assertNull(budgetTotal(project));
        assertEquals("UNAVAILABLE", budgetStatus(project));
    }

    @Test void updatesRequirementsAndIncrementsRevision() {
        var created = service.createProject(request("项目", List.of("一层")));
        var update = new UpdateOfficeRequirementsRequest();
        update.setCustomerRequirements("");
        var updated = service.updateRequirements(created.getProjectId(), update);
        assertEquals("", updated.getCustomerRequirements());
        assertEquals(2, updated.getRequirementsRevision());
        assertEquals(1, updated.getFloors().size());
        assertTrue(updated.getUpdatedAt().isAfter(created.getUpdatedAt()) || updated.getUpdatedAt().equals(created.getUpdatedAt()));
    }

    @Test void updatesProjectAndFloorNamesWithoutChangingIds() {
        var created = service.createProject(request("项目", List.of("一层", "二层")));
        var projectUpdate = new UpdateOfficeProjectRequest();
        projectUpdate.setName("总部办公楼");
        var updatedProject = service.updateProject(created.getProjectId(), projectUpdate);
        assertEquals("总部办公楼", updatedProject.getName());
        assertEquals(created.getProjectId(), updatedProject.getProjectId());

        String floorId = created.getFloors().get(0).getFloorId();
        var floorUpdate = new CreateOfficeFloorRequest();
        floorUpdate.setName("首层");
        var updatedFloor = service.updateFloor(created.getProjectId(), floorId, floorUpdate);
        assertEquals("首层", updatedFloor.getName());
        assertEquals(floorId, updatedFloor.getFloorId());
        floorUpdate.setName("二层");
        assertEquals("楼层名称不能重复", assertThrows(BusinessException.class,
            () -> service.updateFloor(created.getProjectId(), floorId, floorUpdate)).getMessage());
        assertEquals("楼层不存在", assertThrows(BusinessException.class,
            () -> service.updateFloor(created.getProjectId(), "UNKNOWN", floorUpdate)).getMessage());
    }

    @Test void addsFloorWithNextSortOrderAndRejectsDuplicateIgnoringCaseAndWhitespace() {
        var created = service.createProject(request("项目", List.of("一层", "二层")));
        var floor = new CreateOfficeFloorRequest();
        floor.setName(" 三层 ");
        var added = service.addFloor(created.getProjectId(), floor);
        assertEquals(2, added.getSortOrder());
        assertEquals("三层", added.getName());
        floor.setName("  三层  ");
        assertEquals("楼层名称不能重复", assertThrows(BusinessException.class,
            () -> service.addFloor(created.getProjectId(), floor)).getMessage());
        floor.setName("First Floor");
        service.addFloor(created.getProjectId(), floor);
        floor.setName("first floor");
        assertEquals("楼层名称不能重复", assertThrows(BusinessException.class,
            () -> service.addFloor(created.getProjectId(), floor)).getMessage());
        floor.setName("一 层");
        assertDoesNotThrow(() -> service.addFloor(created.getProjectId(), floor));
    }

    @Test void rejectsEmptyNamesEmptyPlansDuplicateFloorsAndUnknownParents() {
        assertEquals("项目名称不能为空", assertThrows(BusinessException.class,
            () -> service.createProject(request(" ", List.of("一层")))).getMessage());
        assertEquals("至少规划一个楼层", assertThrows(BusinessException.class,
            () -> service.createProject(request("项目", List.of()))).getMessage());
        assertEquals("楼层名称不能为空", assertThrows(BusinessException.class,
            () -> service.createProject(request("项目", List.of(" ")))).getMessage());
        assertEquals("楼层名称不能重复", assertThrows(BusinessException.class,
            () -> service.createProject(request("项目", List.of("一层", " 一层 ")))).getMessage());
        assertTrue(projects.isEmpty());
        assertEquals("项目不存在", assertThrows(BusinessException.class,
            () -> service.addFloor("UNKNOWN", new CreateOfficeFloorRequest())).getMessage());
        assertEquals("项目不存在", assertThrows(BusinessException.class,
            () -> service.updateRequirements("UNKNOWN", new UpdateOfficeRequirementsRequest())).getMessage());

        var created = service.createProject(request("项目", List.of("一层")));
        assertEquals("客户需求不能为空", assertThrows(BusinessException.class,
            () -> service.updateRequirements(created.getProjectId(), new UpdateOfficeRequirementsRequest())).getMessage());
    }

    @Test void acceptsOnlyKnownLegacyOrCreatedFloorIds() {
        var legacy = new OfficeDesignFloor();
        legacy.setFloorId("OFFICE-PROJECT-001");
        legacy.setProjectId("OFFICE-PROJECT-001");
        floors.put(legacy.getFloorId(), legacy);
        assertDoesNotThrow(() -> service.requireFloor("OFFICE-PROJECT-001"));
        assertEquals("楼层不存在，请先创建项目和楼层", assertThrows(BusinessException.class,
            () -> service.requireFloor("UNKNOWN-WORKSPACE")).getMessage());
    }

    @Test void createsProjectWithLeaderCollaboratorsAndReferenceImages() {
        activeUser("USR-1", "leader");
        activeUser("USR-2", "collab");
        var request = request("总部", List.of("一层"));
        request.setLeaderUserId("USR-1");
        request.setCollaboratorUserIds(List.of("USR-2"));
        var image = new ProjectReferenceImage();
        image.setUrl("/uploads/images/abc.png");
        image.setName("风格参考");
        request.setReferenceImages(List.of(image));

        var created = service.createProject(request);
        assertEquals("USR-1", created.getLeader().getUserId());
        assertEquals("LEADER", created.getLeader().getRole());
        assertEquals("leader 显示名", created.getLeader().getDisplayName());
        assertEquals(1, created.getCollaborators().size());
        assertEquals("USR-2", created.getCollaborators().get(0).getUserId());
        assertEquals(1, created.getReferenceImages().size());
        assertEquals("/uploads/images/abc.png", created.getReferenceImages().get(0).getUrl());
        assertEquals(2, members.size());
        assertEquals(1, members.stream().filter(member -> "LEADER".equals(member.getRole())).count());

        var tree = service.listProjects();
        assertEquals("USR-1", tree.get(0).getLeader().getUserId());
        assertEquals(1, tree.get(0).getReferenceImages().size());
    }

    @Test void rejectsInvalidMemberSetsAndExternalImageUrls() {
        activeUser("USR-1", "leader");
        activeUser("USR-2", "collab");
        var disabled = activeUser("USR-3", "disabled");
        disabled.setStatus("disabled");

        var missingLeader = request("项目", List.of("一层"));
        missingLeader.setCollaboratorUserIds(List.of("USR-2"));
        assertEquals("项目负责人不能为空", assertThrows(BusinessException.class,
            () -> service.createProject(missingLeader)).getMessage());

        var unknownLeader = request("项目", List.of("一层"));
        unknownLeader.setLeaderUserId("USR-X");
        assertEquals("项目负责人不存在或已停用", assertThrows(BusinessException.class,
            () -> service.createProject(unknownLeader)).getMessage());

        var disabledLeader = request("项目", List.of("一层"));
        disabledLeader.setLeaderUserId("USR-3");
        assertEquals("项目负责人不存在或已停用", assertThrows(BusinessException.class,
            () -> service.createProject(disabledLeader)).getMessage());

        var overlap = request("项目", List.of("一层"));
        overlap.setLeaderUserId("USR-1");
        overlap.setCollaboratorUserIds(List.of("USR-1"));
        assertEquals("负责人不能同时是协作者", assertThrows(BusinessException.class,
            () -> service.createProject(overlap)).getMessage());

        var duplicated = request("项目", List.of("一层"));
        duplicated.setLeaderUserId("USR-1");
        duplicated.setCollaboratorUserIds(List.of("USR-2", "USR-2"));
        assertEquals("协作者不能重复", assertThrows(BusinessException.class,
            () -> service.createProject(duplicated)).getMessage());

        var externalImage = request("项目", List.of("一层"));
        externalImage.setLeaderUserId("USR-1");
        var image = new ProjectReferenceImage();
        image.setUrl("https://example.com/evil.png");
        externalImage.setReferenceImages(List.of(image));
        assertEquals("参考图片地址无效，仅支持本站上传的图片", assertThrows(BusinessException.class,
            () -> service.createProject(externalImage)).getMessage());

        assertTrue(projects.isEmpty());
        assertTrue(members.isEmpty());
    }

    @Test void replaceMembersAtomicallySwapsLeaderAndCollaborators() {
        activeUser("USR-1", "leader");
        activeUser("USR-2", "collab");
        activeUser("USR-3", "newleader");
        var request = request("项目", List.of("一层"));
        request.setLeaderUserId("USR-1");
        request.setCollaboratorUserIds(List.of("USR-2"));
        var created = service.createProject(request);

        var update = new UpdateProjectMembersRequest();
        when(access.ownsProject(created.getProjectId())).thenReturn(true);
        update.setLeaderUserId("USR-3");
        update.setCollaboratorUserIds(List.of("USR-1"));
        var updated = service.replaceMembers(created.getProjectId(), update);
        verify(access).requireLeader(created.getProjectId());

        assertEquals("USR-3", updated.getLeader().getUserId());
        assertEquals(List.of("USR-1"), updated.getCollaborators().stream().map(member -> member.getUserId()).toList());
        assertEquals(2, members.size());
        assertEquals(1, members.stream().filter(member -> "LEADER".equals(member.getRole())).count());
        assertEquals("USR-3", members.stream().filter(member -> "LEADER".equals(member.getRole())).findFirst().orElseThrow().getUserId());
        assertEquals(1, auditLogs.size());
        assertEquals("MEMBERS_REPLACED", auditLogs.get(0).getAction());
        assertEquals(created.getProjectId(), auditLogs.get(0).getTargetId());
        assertTrue(auditLogs.get(0).getDetail().contains("LEADER:USR-1"));
        assertTrue(auditLogs.get(0).getDetail().contains("LEADER:USR-3"));
    }

    @Test void createProjectRequiresWorkspaceAndValidatesMemberWorkspaceWhenEnforced() {
        when(access.workspaceForProjectCreation()).thenReturn("W1");
        var request = request("项目", List.of("一层"));

        // 项目落在经销商工作区 W1，但负责人账号在 W2：拒绝
        activeUserIn("USR-1", "leader", "W2");
        request.setLeaderUserId("USR-1");
        assertEquals("项目负责人不属于本项目工作区", assertThrows(BusinessException.class,
            () -> service.createProject(request)).getMessage());
        assertTrue(projects.isEmpty());

        // 负责人同属 W1：项目落在 W1
        activeUserIn("USR-2", "leader1", "W1");
        request.setLeaderUserId("USR-2");
        var created = service.createProject(request);
        assertEquals("W1", projects.get(created.getProjectId()).getWorkspaceId());
        assertEquals("USR-2", created.getLeader().getUserId());
    }

    @Test void replaceMembersRejectsCollaboratorOutsideProjectWorkspace() {
        when(access.enforcementEnabled()).thenReturn(true);
        when(access.workspaceForProjectCreation()).thenReturn("W1");
        var operator = activeUserIn("USR-1", "leader", "W1");
        when(access.currentUserOrNull()).thenReturn(operator);
        activeUserIn("USR-2", "collab", "W1");
        activeUserIn("USR-3", "outsider", "W2");
        var request = request("项目", List.of("一层"));
        request.setLeaderUserId("USR-1");
        request.setCollaboratorUserIds(List.of("USR-2"));
        var created = service.createProject(request);

        var update = new UpdateProjectMembersRequest();
        update.setLeaderUserId("USR-1");
        update.setCollaboratorUserIds(List.of("USR-3"));
        assertEquals("协作者不属于本项目工作区", assertThrows(BusinessException.class,
            () -> service.replaceMembers(created.getProjectId(), update)).getMessage());
        // 原成员关系未被破坏
        assertEquals(2, members.size());
        assertTrue(auditLogs.isEmpty());
    }

    @Test void assignableUsersScopedToOperatorWorkspaceWhenEnforced() {
        when(access.enforcementEnabled()).thenReturn(true);
        var operator = activeUserIn("USR-1", "alpha", "W1");
        when(access.currentUserOrNull()).thenReturn(operator);
        activeUserIn("USR-2", "beta", "W1");
        activeUserIn("USR-3", "gamma", "W2");
        var result = service.listAssignableUsers(null);
        assertEquals(2, result.size());
        assertTrue(result.stream().noneMatch(user -> user.getUserId().equals("USR-3")));

        // 无工作区账号（如系统管理员）看不到任何账号池
        var admin = activeUser("USR-9", "root");
        when(access.currentUserOrNull()).thenReturn(admin);
        assertTrue(service.listAssignableUsers(null).isEmpty());
    }

    @Test void updateReferencesReplacesImagesWithValidation() {
        activeUser("USR-1", "leader");
        var request = request("项目", List.of("一层"));
        request.setLeaderUserId("USR-1");
        var created = service.createProject(request);

        var update = new UpdateProjectReferencesRequest();
        var first = new ProjectReferenceImage();
        first.setUrl("/uploads/images/a.png");
        first.setName("第一张");
        var second = new ProjectReferenceImage();
        second.setUrl("/uploads/images/b.webp");
        update.setReferenceImages(List.of(first, second));
        var updated = service.updateReferences(created.getProjectId(), update);
        assertEquals(2, updated.getReferenceImages().size());
        assertEquals("", updated.getReferenceImages().get(1).getName());

        var invalid = new UpdateProjectReferencesRequest();
        var bad = new ProjectReferenceImage();
        bad.setUrl("/uploads/images/../secret.png");
        invalid.setReferenceImages(List.of(bad));
        assertEquals("参考图片地址无效，仅支持本站上传的图片", assertThrows(BusinessException.class,
            () -> service.updateReferences(created.getProjectId(), invalid)).getMessage());
    }

    @Test void listProjectsFiltersToAccessibleProjectsWhenEnforced() {
        activeUser("USR-1", "leader");
        var withMembers = request("有成员", List.of("一层"));
        withMembers.setLeaderUserId("USR-1");
        var created = service.createProject(withMembers);
        service.createProject(request("无成员", List.of("一层")));

        when(access.accessibleProjectIds()).thenReturn(java.util.Set.of(created.getProjectId()));
        var tree = service.listProjects();
        assertEquals(1, tree.size());
        assertEquals(created.getProjectId(), tree.get(0).getProjectId());
    }

    @Test void assignableUsersExposeLimitedFieldsOfActiveUsers() {
        activeUser("USR-1", "alpha");
        activeUser("USR-2", "beta");
        var disabled = activeUser("USR-3", "gamma");
        disabled.setStatus("disabled");
        var result = service.listAssignableUsers(null);
        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(user -> !user.getUserId().equals("USR-3")));
        assertEquals("alpha", result.get(0).getUsername());
    }

    private OfficeProjectService budgetService() {
        try {
            return OfficeProjectService.class.getConstructor(
                OfficeDesignProjectMapper.class, OfficeDesignFloorMapper.class, OfficeProjectMemberMapper.class,
                OfficeUserMapper.class, OfficeProjectAccessService.class, OfficeAuditService.class, ObjectMapper.class,
                OfficeCartMapper.class, RsdpCatalogGateway.class, SharedCatalogService.class
            ).newInstance(projectMapper, floorMapper, memberMapper, userMapper, access, auditService, new ObjectMapper(),
                cartMapper, catalogGateway, sharedCatalogService);
        } catch (NoSuchMethodException missingBudgetDependencies) {
            throw new AssertionError("项目服务尚未提供购物车预算聚合依赖", missingBudgetDependencies);
        } catch (ReflectiveOperationException failed) {
            throw new AssertionError("无法构造带预算聚合能力的项目服务", failed);
        }
    }

    private OfficeProjectDto project(List<OfficeProjectDto> projects, String projectId) {
        return projects.stream().filter(project -> projectId.equals(project.getProjectId())).findFirst().orElseThrow();
    }

    private BigDecimal budgetTotal(OfficeProjectDto project) {
        return (BigDecimal) budgetValue(project, "getBudgetTotal");
    }

    private String budgetStatus(OfficeProjectDto project) {
        return (String) budgetValue(project, "getBudgetStatus");
    }

    private Object budgetValue(OfficeProjectDto project, String getter) {
        try {
            return project.getClass().getMethod(getter).invoke(project);
        } catch (ReflectiveOperationException missingBudgetField) {
            throw new AssertionError("项目 DTO 尚未提供预算字段 " + getter, missingBudgetField);
        }
    }

    private OfficeCart cart(String floorId, String productId, int qty) {
        var cart = new OfficeCart();
        cart.setProjectId(floorId);
        cart.setProductId(productId);
        cart.setQty(qty);
        return cart;
    }

    private CreateOfficeProjectRequest request(String name, List<String> floorNames) {
        var request = new CreateOfficeProjectRequest();
        request.setName(name);
        request.setCustomerRequirements("需要开放办公区");
        request.setFloors(floorNames.stream().map(floorName -> {
            var floor = new CreateOfficeFloorRequest();
            floor.setName(floorName);
            return floor;
        }).toList());
        return request;
    }

    private List<String> scopeIds(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?> query) {
        query.getSqlSegment();
        return query.getParamNameValuePairs().values().stream().flatMap(value -> {
            if (value instanceof Collection<?> values) return values.stream();
            return java.util.stream.Stream.of(value);
        }).filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
