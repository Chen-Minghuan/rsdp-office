package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.mapper.OfficeUserMapper;
import com.rsdp.officeproduct.importer.service.RsdpCatalogGateway;
import com.rsdp.officeproduct.importer.office.dto.CreateOfficeFloorRequest;
import com.rsdp.officeproduct.importer.office.dto.CreateOfficeProjectRequest;
import com.rsdp.officeproduct.importer.office.dto.OfficeFloorDto;
import com.rsdp.officeproduct.importer.office.dto.OfficeProjectDto;
import com.rsdp.officeproduct.importer.office.dto.ProjectReferenceImage;
import com.rsdp.officeproduct.importer.office.dto.UpdateOfficeProjectRequest;
import com.rsdp.officeproduct.importer.office.dto.UpdateOfficeRequirementsRequest;
import com.rsdp.officeproduct.importer.office.dto.UpdateProjectMembersRequest;
import com.rsdp.officeproduct.importer.office.dto.UpdateProjectReferencesRequest;
import com.rsdp.officeproduct.importer.office.entity.OfficeCart;
import com.rsdp.officeproduct.importer.office.entity.OfficeCanvas;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignFloor;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import com.rsdp.officeproduct.importer.office.entity.OfficeAuditLog;
import com.rsdp.officeproduct.importer.office.entity.OfficeProjectMember;
import com.rsdp.officeproduct.importer.office.mapper.OfficeAuditLogMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCanvasMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCartMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignFloorMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignProjectMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProjectMemberMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProposalConfirmationMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceLockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OfficeProjectServiceTest {
    private final Map<String, OfficeDesignProject> projects = new LinkedHashMap<>();
    private final Map<String, OfficeDesignFloor> floors = new LinkedHashMap<>();
    private final Map<String, OfficeUser> users = new LinkedHashMap<>();
    private final List<OfficeProjectMember> members = new ArrayList<>();
    private final List<OfficeAuditLog> auditLogs = new ArrayList<>();
    private final List<OfficeCart> cartLines = new ArrayList<>();
    private final java.util.Set<String> confirmedFloorIds = new java.util.HashSet<>();
    private final Map<String, String> canvasObjectsByFloor = new LinkedHashMap<>();
    private OfficeDesignProjectMapper projectMapper;
    private OfficeDesignFloorMapper floorMapper;
    private OfficeProjectMemberMapper memberMapper;
    private OfficeUserMapper userMapper;
    private OfficeProjectAccessService access;
    private OfficeAuditService auditService;
    private OfficeCartMapper cartMapper;
    private RsdpCatalogGateway catalogGateway;
    private SharedCatalogService sharedCatalogService;
    private OfficeProposalConfirmationMapper confirmationMapper;
    private OfficeCanvasMapper canvasMapper;
    private OfficeWorkspaceLockMapper workspaceLocks;
    private OfficeProjectService service;

    @BeforeEach void setup() {
        projects.clear();
        floors.clear();
        users.clear();
        members.clear();
        auditLogs.clear();
        cartLines.clear();
        confirmedFloorIds.clear();
        canvasObjectsByFloor.clear();
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
        when(floorMapper.updateById(any(OfficeDesignFloor.class))).thenAnswer(call -> { floors.put(call.getArgument(0, OfficeDesignFloor.class).getFloorId(), call.getArgument(0)); return 1; });
        // 定点列更新：只把本列写回存储实体，与生产 SQL 的单列 UPDATE 语义一致。
        when(floorMapper.updateName(anyString(), anyString(), any())).thenAnswer(call -> {
            OfficeDesignFloor floor = floors.get(call.getArgument(0));
            if (floor == null) return 0;
            floor.setName(call.getArgument(1));
            floor.setUpdatedAt(call.getArgument(2));
            return 1;
        });
        when(floorMapper.updateReferenceImages(anyString(), anyString(), any())).thenAnswer(call -> {
            OfficeDesignFloor floor = floors.get(call.getArgument(0));
            if (floor == null) return 0;
            floor.setReferenceImages(call.getArgument(1));
            floor.setUpdatedAt(call.getArgument(2));
            return 1;
        });
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
        confirmationMapper = mock(OfficeProposalConfirmationMapper.class);
        when(confirmationMapper.confirmedFloorIds(anyList())).thenAnswer(call ->
            ((List<String>) call.getArgument(0)).stream().filter(confirmedFloorIds::contains).toList());
        canvasMapper = mock(OfficeCanvasMapper.class);
        when(canvasMapper.selectLatestByFloorIds(anyList())).thenAnswer(call ->
            ((List<String>) call.getArgument(0)).stream()
                .filter(canvasObjectsByFloor::containsKey)
                .map(floorId -> {
                    var canvas = new OfficeCanvas();
                    canvas.setProjectId(floorId);
                    canvas.setObjectsJson(canvasObjectsByFloor.get(floorId));
                    return canvas;
                }).toList());
        workspaceLocks = mock(OfficeWorkspaceLockMapper.class);
        when(workspaceLocks.lockWorkspace(anyString())).thenReturn(1);
        service = new OfficeProjectService(projectMapper, floorMapper, memberMapper, userMapper, access,
            auditService, new ObjectMapper(), cartMapper, catalogGateway, sharedCatalogService, confirmationMapper,
            canvasMapper, workspaceLocks);
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

    @Test void projectProgressCountsCartedZonesOnConfirmedFloors() {
        var created = service.createProject(request("进度项目", List.of("一层", "二层")));
        String firstFloorId = created.getFloors().get(0).getFloorId();
        String secondFloorId = created.getFloors().get(1).getFloorId();
        canvasObjectsByFloor.put(firstFloorId, objectsJson("F1", 3));
        canvasObjectsByFloor.put(secondFloorId, objectsJson("F2", 2));
        // 一层：F1-Z0/F1-Z1 购物车有量，F1-Z2 无购物车行 → 交付中 2
        cartLines.add(cart(firstFloorId, "V-1", 1, "F1-Z0"));
        cartLines.add(cart(firstFloorId, "V-1", 1, "F1-Z1"));
        // 二层：F2-Z0 有量，F2-Z1 数量为 0 同样视为设计中 → 交付中 1
        cartLines.add(cart(secondFloorId, "V-2", 2, "F2-Z0"));
        cartLines.add(cart(secondFloorId, "V-2", 0, "F2-Z1"));

        var before = service.listProjects(false).getFirst();
        assertEquals(5, before.getZoneTotal());
        assertEquals(0, before.getZoneDelivered());

        confirmedFloorIds.add(firstFloorId);
        var partial = service.listProjects(false).getFirst();
        assertEquals(5, partial.getZoneTotal());
        assertEquals(2, partial.getZoneDelivered());

        confirmedFloorIds.add(secondFloorId);
        var all = service.listProjects(false).getFirst();
        assertEquals(5, all.getZoneTotal());
        assertEquals(3, all.getZoneDelivered());
    }

    @Test void projectProgressIsZeroWithoutCanvasAndSurvivesBrokenCanvasJson() {
        var noCanvas = service.createProject(request("无画布", List.of("一层")));
        var listed = service.listProjects(false).getFirst();
        assertEquals(0, listed.getZoneTotal());
        assertEquals(0, listed.getZoneDelivered());

        var broken = service.createProject(request("坏画布", List.of("一层")));
        canvasObjectsByFloor.put(broken.getFloors().getFirst().getFloorId(), "{not-json");
        var survived = service.listProjects(false);
        assertEquals(2, survived.size());
        assertEquals(0, survived.get(1).getZoneTotal());
        assertEquals(0, survived.get(1).getZoneDelivered());
    }

    @Test void listsProjectsWithoutReadingCatalogPricingWhenBudgetIsExcluded() {
        var created = service.createProject(request("总部", List.of("一层")));
        cartLines.add(cart(created.getFloors().getFirst().getFloorId(), "V-1", 1));

        var projects = service.listProjects(false);

        assertEquals(created.getProjectId(), projects.getFirst().getProjectId());
        assertNull(projects.getFirst().getBudgetTotal());
        assertNull(projects.getFirst().getBudgetStatus());
        // 进度口径需要读取本地购物车表；预算排除时不得触发目录定价
        verifyNoInteractions(catalogGateway);
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

    @Test void replaceMembersWritesEncodedPermissionFlags() {
        activeUser("USR-1", "leader");
        activeUser("USR-2", "collab");
        var request = request("项目", List.of("一层"));
        request.setLeaderUserId("USR-1");
        request.setCollaboratorUserIds(List.of("USR-2"));
        var created = service.createProject(request);
        // 创建项目时不附带授权：新成员 permission_flags 为 0
        assertEquals(0, member(created.getProjectId(), "USR-1").getPermissionFlags());
        assertEquals(0, member(created.getProjectId(), "USR-2").getPermissionFlags());

        when(access.ownsProject(created.getProjectId())).thenReturn(true);
        var update = new UpdateProjectMembersRequest();
        update.setLeaderUserId("USR-1");
        update.setCollaboratorUserIds(List.of("USR-2"));
        update.setPermissions(Map.of(
            "USR-1", List.of(OfficeProjectPermission.READ, OfficeProjectPermission.EDIT, OfficeProjectPermission.MANAGE_MEMBERS),
            "USR-2", List.of(OfficeProjectPermission.READ, OfficeProjectPermission.QUOTE_READ, OfficeProjectPermission.EXPORT_QUOTE)));
        var updated = service.replaceMembers(created.getProjectId(), update);

        // 位掩码真实写入成员行：负责人 1|2|32=35，协作者 1|4|64=69
        assertEquals(35, member(created.getProjectId(), "USR-1").getPermissionFlags());
        assertEquals(69, member(created.getProjectId(), "USR-2").getPermissionFlags());
        // 返回 DTO 从位掩码回读出权限（toMemberDto → OfficeProjectPermission.assigned 链路）
        assertEquals(List.of(OfficeProjectPermission.READ, OfficeProjectPermission.EDIT, OfficeProjectPermission.MANAGE_MEMBERS),
            updated.getLeader().getPermissions());
        assertEquals(List.of(OfficeProjectPermission.READ, OfficeProjectPermission.QUOTE_READ, OfficeProjectPermission.EXPORT_QUOTE),
            updated.getCollaborators().get(0).getPermissions());
        // 审计 detail 记录替换前后的授权文本
        String detail = auditLogs.getLast().getDetail();
        assertTrue(detail.contains("LEADER:USR-1=[READ, EDIT, MANAGE_MEMBERS]"));
        assertTrue(detail.contains("COLLABORATOR:USR-2=[READ, QUOTE_READ, EXPORT_QUOTE]"));
    }

    @Test void replaceMembersKeepsExistingGrantsWhenPermissionsOmitted() {
        activeUser("USR-1", "leader");
        activeUser("USR-2", "collab");
        activeUser("USR-3", "other");
        var request = request("项目", List.of("一层"));
        request.setLeaderUserId("USR-1");
        request.setCollaboratorUserIds(List.of("USR-2"));
        var created = service.createProject(request);

        when(access.ownsProject(created.getProjectId())).thenReturn(true);
        var grant = new UpdateProjectMembersRequest();
        grant.setLeaderUserId("USR-1");
        grant.setCollaboratorUserIds(List.of("USR-2"));
        grant.setPermissions(Map.of(
            "USR-1", List.of(OfficeProjectPermission.READ, OfficeProjectPermission.EDIT, OfficeProjectPermission.MANAGE_MEMBERS),
            "USR-2", List.of(OfficeProjectPermission.READ, OfficeProjectPermission.QUOTE_READ)));
        service.replaceMembers(created.getProjectId(), grant);
        assertEquals(35, member(created.getProjectId(), "USR-1").getPermissionFlags());
        assertEquals(5, member(created.getProjectId(), "USR-2").getPermissionFlags());

        // 非主号、负责人不变、不传 permissions：只调整协作者集合，既有授权必须保留
        when(access.ownsProject(created.getProjectId())).thenReturn(false);
        var swap = new UpdateProjectMembersRequest();
        swap.setLeaderUserId("USR-1");
        swap.setCollaboratorUserIds(List.of("USR-3"));
        var updated = service.replaceMembers(created.getProjectId(), swap);

        assertEquals(35, member(created.getProjectId(), "USR-1").getPermissionFlags());
        assertEquals(0, member(created.getProjectId(), "USR-3").getPermissionFlags());
        assertTrue(members.stream().noneMatch(member -> "USR-2".equals(member.getUserId())));
        assertEquals(List.of("USR-3"), updated.getCollaborators().stream().map(member -> member.getUserId()).toList());
        verify(access, times(2)).requireLeader(created.getProjectId());
    }

    @Test void replaceMembersRejectsGrantsForNonMembers() {
        activeUser("USR-1", "leader");
        activeUser("USR-2", "collab");
        activeUser("USR-9", "outsider");
        var request = request("项目", List.of("一层"));
        request.setLeaderUserId("USR-1");
        request.setCollaboratorUserIds(List.of("USR-2"));
        var created = service.createProject(request);

        when(access.ownsProject(created.getProjectId())).thenReturn(true);
        var update = new UpdateProjectMembersRequest();
        update.setLeaderUserId("USR-1");
        update.setCollaboratorUserIds(List.of("USR-2"));
        update.setPermissions(Map.of("USR-9", List.of(OfficeProjectPermission.READ)));
        assertEquals("授权对象必须是本项目成员", assertThrows(BusinessException.class,
            () -> service.replaceMembers(created.getProjectId(), update)).getMessage());
        // 校验失败发生在先删后插之前，成员关系不被破坏
        verify(memberMapper, never()).delete(any());
        assertEquals(2, members.size());
        assertTrue(auditLogs.isEmpty());
    }

    @Test void replaceMembersRejectsInvalidPermissionCombinations() {
        activeUser("USR-1", "leader");
        activeUser("USR-2", "collab");
        var request = request("项目", List.of("一层"));
        request.setLeaderUserId("USR-1");
        request.setCollaboratorUserIds(List.of("USR-2"));
        var created = service.createProject(request);
        when(access.ownsProject(created.getProjectId())).thenReturn(true);

        var missingRead = new UpdateProjectMembersRequest();
        missingRead.setLeaderUserId("USR-1");
        missingRead.setCollaboratorUserIds(List.of("USR-2"));
        missingRead.setPermissions(Map.of("USR-2", List.of(OfficeProjectPermission.EDIT)));
        assertEquals("其他项目权限需要先勾选项目查看", assertThrows(BusinessException.class,
            () -> service.replaceMembers(created.getProjectId(), missingRead)).getMessage());

        var missingQuoteRead = new UpdateProjectMembersRequest();
        missingQuoteRead.setLeaderUserId("USR-1");
        missingQuoteRead.setCollaboratorUserIds(List.of("USR-2"));
        missingQuoteRead.setPermissions(Map.of("USR-2",
            List.of(OfficeProjectPermission.READ, OfficeProjectPermission.MARGIN_READ)));
        assertEquals("毛利查看和报价导出需要先勾选报价查看", assertThrows(BusinessException.class,
            () -> service.replaceMembers(created.getProjectId(), missingQuoteRead)).getMessage());

        verify(memberMapper, never()).delete(any());
        assertEquals(2, members.size());
        assertTrue(auditLogs.isEmpty());
    }

    @Test void replaceMembersRejectsGrantingOrLeaderChangeForNonOwner() {
        activeUser("USR-1", "leader");
        activeUser("USR-2", "collab");
        activeUser("USR-3", "other");
        var request = request("项目", List.of("一层"));
        request.setLeaderUserId("USR-1");
        request.setCollaboratorUserIds(List.of("USR-2"));
        var created = service.createProject(request);
        when(access.ownsProject(created.getProjectId())).thenReturn(false);

        // 非主号传 permissions：即使负责人不变也拒绝
        var granting = new UpdateProjectMembersRequest();
        granting.setLeaderUserId("USR-1");
        granting.setCollaboratorUserIds(List.of("USR-2"));
        granting.setPermissions(Map.of("USR-2", List.of(OfficeProjectPermission.READ)));
        assertEquals("只有工作区主号可以授权或更换负责人",
            assertThrows(com.rsdp.officeproduct.importer.common.AccessDeniedException.class,
                () -> service.replaceMembers(created.getProjectId(), granting)).getMessage());

        // 非主号更换负责人：即使不传 permissions 也拒绝
        var leaderChange = new UpdateProjectMembersRequest();
        leaderChange.setLeaderUserId("USR-3");
        leaderChange.setCollaboratorUserIds(List.of("USR-2"));
        assertEquals("只有工作区主号可以授权或更换负责人",
            assertThrows(com.rsdp.officeproduct.importer.common.AccessDeniedException.class,
                () -> service.replaceMembers(created.getProjectId(), leaderChange)).getMessage());

        // 非主号仅调整协作者集合：放行
        var collaboratorsOnly = new UpdateProjectMembersRequest();
        collaboratorsOnly.setLeaderUserId("USR-1");
        collaboratorsOnly.setCollaboratorUserIds(List.of("USR-3"));
        assertDoesNotThrow(() -> service.replaceMembers(created.getProjectId(), collaboratorsOnly));

        // 两次拒绝均未触碰成员表；MANAGE_MEMBERS 前置检查（requireLeader）对三次调用恒在
        verify(memberMapper, times(1)).delete(any());
        assertEquals(2, members.size());
        assertTrue(members.stream().anyMatch(member -> "USR-3".equals(member.getUserId())));
        verify(access, times(3)).requireLeader(created.getProjectId());
    }

    private OfficeProjectMember member(String projectId, String userId) {
        return members.stream()
            .filter(member -> projectId.equals(member.getProjectId()) && userId.equals(member.getUserId()))
            .findFirst().orElseThrow();
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

    private record FloorFixture(String projectId, String floorId) {}

    private FloorFixture projectWithFloor() {
        activeUser("USR-1", "leader");
        var request = request("项目", List.of("一层"));
        request.setLeaderUserId("USR-1");
        var created = service.createProject(request);
        return new FloorFixture(created.getProjectId(), created.getFloors().get(0).getFloorId());
    }

    private ProjectReferenceImage referenceImage(String url, String name) {
        var image = new ProjectReferenceImage();
        image.setUrl(url);
        image.setName(name);
        return image;
    }

    private UpdateProjectReferencesRequest referencesRequest(ProjectReferenceImage... images) {
        var update = new UpdateProjectReferencesRequest();
        update.setReferenceImages(List.of(images));
        return update;
    }

    @Test void updateFloorReferencesReplacesImagesAndPersistsJson() {
        var fixture = projectWithFloor();
        var updated = service.updateFloorReferences(fixture.projectId(), fixture.floorId(), referencesRequest(
            referenceImage("/uploads/images/a.png", "  风格参考  "),
            referenceImage("/uploads/images/b.webp", null)));

        assertEquals(2, updated.getReferenceImages().size());
        assertEquals("风格参考", updated.getReferenceImages().get(0).getName());
        assertEquals("", updated.getReferenceImages().get(1).getName());
        var stored = floors.get(fixture.floorId());
        assertNotNull(stored.getUpdatedAt());
        assertTrue(stored.getReferenceImages().contains("/uploads/images/a.png"));
        verify(access).requirePermission(fixture.projectId(), OfficeProjectPermission.EDIT);

        var replaced = service.updateFloorReferences(fixture.projectId(), fixture.floorId(),
            referencesRequest(referenceImage("/uploads/images/c.png", "替换后")));
        assertEquals(1, replaced.getReferenceImages().size());
        assertEquals("/uploads/images/c.png", replaced.getReferenceImages().get(0).getUrl());
        assertFalse(floors.get(fixture.floorId()).getReferenceImages().contains("/uploads/images/a.png"));
    }

    @Test void updateFloorReferencesLocksFloorBeforeReading() {
        var fixture = projectWithFloor();
        service.updateFloorReferences(fixture.projectId(), fixture.floorId(),
            referencesRequest(referenceImage("/uploads/images/a.png", "图")));
        // 先取事务锁再读楼层：并发保存串行执行，后到的旧请求不能覆盖新列表。
        InOrder order = inOrder(workspaceLocks, floorMapper);
        order.verify(workspaceLocks).lockWorkspace(fixture.floorId());
        order.verify(floorMapper).selectById(fixture.floorId());
    }

    @Test void updateFloorReferencesWithNullOrEmptyListClearsImages() {
        var fixture = projectWithFloor();
        service.updateFloorReferences(fixture.projectId(), fixture.floorId(),
            referencesRequest(referenceImage("/uploads/images/a.png", "基线")));

        var cleared = service.updateFloorReferences(fixture.projectId(), fixture.floorId(),
            new UpdateProjectReferencesRequest());
        assertEquals(List.of(), cleared.getReferenceImages());
        assertEquals("[]", floors.get(fixture.floorId()).getReferenceImages());

        service.updateFloorReferences(fixture.projectId(), fixture.floorId(),
            referencesRequest(referenceImage("/uploads/images/b.png", "再次")));
        var emptied = service.updateFloorReferences(fixture.projectId(), fixture.floorId(), referencesRequest());
        assertEquals(List.of(), emptied.getReferenceImages());
        assertEquals("[]", floors.get(fixture.floorId()).getReferenceImages());
    }

    @Test void updateFloorReferencesRejectsUnknownFloor() {
        var fixture = projectWithFloor();
        var update = referencesRequest(referenceImage("/uploads/images/a.png", "图"));
        assertEquals("楼层不存在", assertThrows(BusinessException.class,
            () -> service.updateFloorReferences(fixture.projectId(), "OFLR-UNKNOWN", update)).getMessage());
        verify(floorMapper, never()).updateReferenceImages(any(), any(), any());
    }

    @Test void updateFloorReferencesRejectsFloorFromAnotherProject() {
        var fixture = projectWithFloor();
        activeUser("USR-2", "other");
        var otherRequest = request("其他项目", List.of("一层"));
        otherRequest.setLeaderUserId("USR-2");
        var other = service.createProject(otherRequest);
        String otherFloorId = other.getFloors().get(0).getFloorId();
        String baseline = floors.get(otherFloorId).getReferenceImages();

        var update = referencesRequest(referenceImage("/uploads/images/a.png", "图"));
        assertEquals("楼层不存在", assertThrows(BusinessException.class,
            () -> service.updateFloorReferences(fixture.projectId(), otherFloorId, update)).getMessage());
        assertEquals(baseline, floors.get(otherFloorId).getReferenceImages());
        verify(floorMapper, never()).updateReferenceImages(any(), any(), any());
    }

    @Test void updateFloorReferencesRejectsNullRequest() {
        var fixture = projectWithFloor();
        assertEquals("参考图片参数不能为空", assertThrows(BusinessException.class,
            () -> service.updateFloorReferences(fixture.projectId(), fixture.floorId(), null)).getMessage());
        verify(floorMapper, never()).updateReferenceImages(any(), any(), any());
    }

    @Test void updateFloorReferencesRejectsInvalidUrls() {
        var fixture = projectWithFloor();
        service.updateFloorReferences(fixture.projectId(), fixture.floorId(),
            referencesRequest(referenceImage("/uploads/images/base.png", "基线")));
        String baseline = floors.get(fixture.floorId()).getReferenceImages();

        assertEquals("参考图片地址无效，仅支持本站上传的图片", assertThrows(BusinessException.class,
            () -> service.updateFloorReferences(fixture.projectId(), fixture.floorId(),
                referencesRequest(referenceImage("https://example.com/evil.png", "外链")))).getMessage());
        assertEquals("参考图片地址无效，仅支持本站上传的图片", assertThrows(BusinessException.class,
            () -> service.updateFloorReferences(fixture.projectId(), fixture.floorId(),
                referencesRequest(referenceImage("/uploads/images/../secret.png", "穿越")))).getMessage());
        assertEquals("参考图片地址不能为空", assertThrows(BusinessException.class,
            () -> service.updateFloorReferences(fixture.projectId(), fixture.floorId(),
                referencesRequest(referenceImage("  ", "空白")))).getMessage());
        assertEquals(baseline, floors.get(fixture.floorId()).getReferenceImages());

        var trimmed = service.updateFloorReferences(fixture.projectId(), fixture.floorId(),
            referencesRequest(referenceImage(" /uploads/images/trim.png ", "前后空格")));
        assertEquals("/uploads/images/trim.png", trimmed.getReferenceImages().get(0).getUrl());
    }

    @Test void updateFloorReferencesRejectsMoreThanTenImages() {
        var fixture = projectWithFloor();
        var eleven = java.util.stream.IntStream.range(0, 11)
            .mapToObj(index -> referenceImage("/uploads/images/" + index + ".png", "图" + index)).toList();
        assertEquals("项目参考图片不能超过 10 张", assertThrows(BusinessException.class,
            () -> service.updateFloorReferences(fixture.projectId(), fixture.floorId(),
                referencesRequest(eleven.toArray(ProjectReferenceImage[]::new)))).getMessage());
        assertNull(floors.get(fixture.floorId()).getReferenceImages());

        var ten = service.updateFloorReferences(fixture.projectId(), fixture.floorId(),
            referencesRequest(eleven.subList(0, 10).toArray(ProjectReferenceImage[]::new)));
        assertEquals(10, ten.getReferenceImages().size());
    }

    @Test void updateFloorReferencesRejectsOverlongName() {
        var fixture = projectWithFloor();
        assertEquals("参考图片名称不能超过 200 个字符", assertThrows(BusinessException.class,
            () -> service.updateFloorReferences(fixture.projectId(), fixture.floorId(),
                referencesRequest(referenceImage("/uploads/images/a.png", "x".repeat(201))))).getMessage());

        var boundary = service.updateFloorReferences(fixture.projectId(), fixture.floorId(),
            referencesRequest(referenceImage("/uploads/images/a.png", "x".repeat(200))));
        assertEquals(200, boundary.getReferenceImages().get(0).getName().length());
    }

    @Test void updateFloorReferencesRequiresEditPermission() {
        var fixture = projectWithFloor();
        doThrow(new com.rsdp.officeproduct.importer.common.AccessDeniedException("无权编辑"))
            .when(access).requirePermission(fixture.projectId(), OfficeProjectPermission.EDIT);
        assertThrows(com.rsdp.officeproduct.importer.common.AccessDeniedException.class,
            () -> service.updateFloorReferences(fixture.projectId(), fixture.floorId(),
                referencesRequest(referenceImage("/uploads/images/a.png", "图"))));
        assertNull(floors.get(fixture.floorId()).getReferenceImages());
        verify(floorMapper, never()).updateReferenceImages(any(), any(), any());
    }

    @Test void consecutiveFloorReferenceEditsKeepLastWriteAfterReread() {
        var fixture = projectWithFloor();
        service.updateFloorReferences(fixture.projectId(), fixture.floorId(), referencesRequest(
            referenceImage("/uploads/images/first.png", "第一次")));
        service.updateFloorReferences(fixture.projectId(), fixture.floorId(), referencesRequest(
            referenceImage("/uploads/images/second.png", "第二次"),
            referenceImage("/uploads/images/second-b.png", "第二次补")));
        var last = service.updateFloorReferences(fixture.projectId(), fixture.floorId(), referencesRequest(
            referenceImage("/uploads/images/third.png", "第三次")));
        assertEquals(1, last.getReferenceImages().size());

        // 重新读取（服务端读路径），确认只保留最后一次修改。
        var reread = rereadFloor(fixture.projectId(), fixture.floorId());
        assertEquals(1, reread.getReferenceImages().size());
        assertEquals("/uploads/images/third.png", reread.getReferenceImages().get(0).getUrl());
        assertEquals("第三次", reread.getReferenceImages().get(0).getName());
        String stored = floors.get(fixture.floorId()).getReferenceImages();
        assertFalse(stored.contains("first.png"));
        assertFalse(stored.contains("second.png"));
    }

    @Test void consecutiveFloorRenamesKeepLastWriteAfterReread() {
        var fixture = projectWithFloor();
        var rename = new CreateOfficeFloorRequest();
        rename.setName("首层");
        service.updateFloor(fixture.projectId(), fixture.floorId(), rename);
        rename.setName("大堂层");
        var renamed = service.updateFloor(fixture.projectId(), fixture.floorId(), rename);
        assertEquals("大堂层", renamed.getName());
        assertEquals("大堂层", rereadFloor(fixture.projectId(), fixture.floorId()).getName());

        // 与参考图编辑交错：两类编辑重新读取后各自保留最后一次。
        service.updateFloorReferences(fixture.projectId(), fixture.floorId(), referencesRequest(
            referenceImage("/uploads/images/ref-1.png", "第一版")));
        rename.setName("最终层");
        service.updateFloor(fixture.projectId(), fixture.floorId(), rename);
        service.updateFloorReferences(fixture.projectId(), fixture.floorId(), referencesRequest(
            referenceImage("/uploads/images/ref-2.png", "第二版")));

        var reread = rereadFloor(fixture.projectId(), fixture.floorId());
        assertEquals("最终层", reread.getName());
        assertEquals(1, reread.getReferenceImages().size());
        assertEquals("/uploads/images/ref-2.png", reread.getReferenceImages().get(0).getUrl());
        String stored = floors.get(fixture.floorId()).getReferenceImages();
        assertFalse(stored.contains("ref-1.png"));
    }

    @Test void concurrentFloorRenameAndReferenceSaveBothSurvive() throws Exception {
        var fixture = projectWithFloor();
        service.updateFloorReferences(fixture.projectId(), fixture.floorId(), referencesRequest(
            referenceImage("/uploads/images/base.png", "基线")));

        // 模拟两个并发事务交错：双方都在对方写入前读到同一行的独立快照（深拷贝），随后各自写回。
        // 定点列更新下两列互不覆盖；旧的整行 updateById 语义会让后写者用旧快照覆盖先写者的列。
        var readBarrier = new java.util.concurrent.CyclicBarrier(2);
        when(floorMapper.selectById(anyString())).thenAnswer(call -> {
            OfficeDesignFloor snapshot = copyFloor(floors.get(call.getArgument(0)));
            if (snapshot != null && fixture.floorId().equals(snapshot.getFloorId())) {
                try {
                    readBarrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception barrierFailure) {
                    throw new AssertionError("并发读取栅栏失败", barrierFailure);
                }
            }
            return snapshot;
        });
        var rename = new CreateOfficeFloorRequest();
        rename.setName("并发改名");
        var referencesUpdate = referencesRequest(referenceImage("/uploads/images/concurrent.png", "并发图"));

        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var renamed = pool.submit(() -> service.updateFloor(fixture.projectId(), fixture.floorId(), rename));
            var saved = pool.submit(() -> service.updateFloorReferences(fixture.projectId(), fixture.floorId(), referencesUpdate));
            renamed.get(15, java.util.concurrent.TimeUnit.SECONDS);
            saved.get(15, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        var reread = rereadFloor(fixture.projectId(), fixture.floorId());
        assertEquals("并发改名", reread.getName());
        assertEquals(1, reread.getReferenceImages().size());
        assertEquals("/uploads/images/concurrent.png", reread.getReferenceImages().get(0).getUrl());
        assertEquals("并发图", reread.getReferenceImages().get(0).getName());
    }

    @Test void floorRenameAndReferenceSaveWriteOnlyTheirOwnColumns() {
        var fixture = projectWithFloor();
        var rename = new CreateOfficeFloorRequest();
        rename.setName("只写名称列");
        service.updateFloor(fixture.projectId(), fixture.floorId(), rename);
        service.updateFloorReferences(fixture.projectId(), fixture.floorId(), referencesRequest(
            referenceImage("/uploads/images/column.png", "列语义")));

        verify(floorMapper).updateName(eq(fixture.floorId()), eq("只写名称列"), any());
        verify(floorMapper).updateReferenceImages(eq(fixture.floorId()), contains("/uploads/images/column.png"), any());
        // 防止回退到整行写：整行 updateById 会在并发交错时用旧快照覆盖另一列。
        verify(floorMapper, never()).updateById(any(OfficeDesignFloor.class));
    }

    private OfficeFloorDto rereadFloor(String projectId, String floorId) {
        return project(service.listProjects(), projectId).getFloors().stream()
            .filter(floor -> floorId.equals(floor.getFloorId())).findFirst().orElseThrow();
    }

    private OfficeDesignFloor copyFloor(OfficeDesignFloor floor) {
        if (floor == null) return null;
        var copy = new OfficeDesignFloor();
        copy.setFloorId(floor.getFloorId());
        copy.setProjectId(floor.getProjectId());
        copy.setName(floor.getName());
        copy.setSortOrder(floor.getSortOrder());
        copy.setReferenceImages(floor.getReferenceImages());
        copy.setCreatedAt(floor.getCreatedAt());
        copy.setUpdatedAt(floor.getUpdatedAt());
        return copy;
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
                OfficeCartMapper.class, RsdpCatalogGateway.class, SharedCatalogService.class,
                OfficeProposalConfirmationMapper.class, OfficeCanvasMapper.class, OfficeWorkspaceLockMapper.class
            ).newInstance(projectMapper, floorMapper, memberMapper, userMapper, access, auditService, new ObjectMapper(),
                cartMapper, catalogGateway, sharedCatalogService, confirmationMapper, canvasMapper, workspaceLocks);
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
        return cart(floorId, productId, qty, null);
    }

    private OfficeCart cart(String floorId, String productId, int qty, String zoneId) {
        var cart = new OfficeCart();
        cart.setProjectId(floorId);
        cart.setProductId(productId);
        cart.setQty(qty);
        cart.setZoneId(zoneId);
        return cart;
    }

    private String objectsJson(String zoneIdPrefix, int zoneCount) {
        var zones = new StringBuilder();
        for (int i = 0; i < zoneCount; i++) {
            if (i > 0) zones.append(',');
            zones.append("{\"id\":\"").append(zoneIdPrefix).append("-Z").append(i).append("\",\"kind\":\"zone\"}");
        }
        return "[" + zones + ",{\"id\":\"F-1\",\"kind\":\"furniture\"}]";
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
