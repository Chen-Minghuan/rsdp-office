package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.mapper.OfficeUserMapper;
import com.rsdp.officeproduct.importer.office.dto.CreateOfficeFloorRequest;
import com.rsdp.officeproduct.importer.office.dto.CreateOfficeProjectRequest;
import com.rsdp.officeproduct.importer.office.dto.ProjectReferenceImage;
import com.rsdp.officeproduct.importer.office.dto.UpdateOfficeProjectRequest;
import com.rsdp.officeproduct.importer.office.dto.UpdateOfficeRequirementsRequest;
import com.rsdp.officeproduct.importer.office.dto.UpdateProjectMembersRequest;
import com.rsdp.officeproduct.importer.office.dto.UpdateProjectReferencesRequest;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignFloor;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import com.rsdp.officeproduct.importer.office.entity.OfficeProjectMember;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignFloorMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignProjectMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProjectMemberMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    private OfficeProjectAccessService access;
    private OfficeProjectService service;

    @BeforeEach void setup() {
        projects.clear();
        floors.clear();
        users.clear();
        members.clear();
        var projectMapper = mock(OfficeDesignProjectMapper.class);
        var floorMapper = mock(OfficeDesignFloorMapper.class);
        var memberMapper = mock(OfficeProjectMemberMapper.class);
        var userMapper = mock(OfficeUserMapper.class);
        access = mock(OfficeProjectAccessService.class);
        when(access.enforcementEnabled()).thenReturn(false);
        when(access.accessibleProjectIds()).thenReturn(null);
        when(projectMapper.insert(any(OfficeDesignProject.class))).thenAnswer(call -> { projects.put(call.getArgument(0, OfficeDesignProject.class).getProjectId(), call.getArgument(0)); return 1; });
        when(projectMapper.updateById(any(OfficeDesignProject.class))).thenAnswer(call -> { projects.put(call.getArgument(0, OfficeDesignProject.class).getProjectId(), call.getArgument(0)); return 1; });
        when(projectMapper.selectById(anyString())).thenAnswer(call -> projects.get(call.getArgument(0)));
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
        when(userMapper.selectById(anyString())).thenAnswer(call -> users.get(call.getArgument(0)));
        when(userMapper.selectBatchIds(any(Collection.class))).thenAnswer(call -> users.values().stream()
            .filter(user -> ((Collection<?>) call.getArgument(0)).contains(user.getUserId())).toList());
        when(userMapper.selectList(any())).thenAnswer(call -> {
            List<String> params = scopeIds(call.getArgument(0));
            return users.values().stream()
                .filter(user -> params.isEmpty() || params.contains(user.getStatus()))
                .toList();
        });
        service = new OfficeProjectService(projectMapper, floorMapper, memberMapper, userMapper, access, new ObjectMapper());
    }

    private OfficeUser activeUser(String userId, String username) {
        var user = new OfficeUser();
        user.setUserId(userId);
        user.setUsername(username);
        user.setDisplayName(username + " 显示名");
        user.setStatus("active");
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
        update.setLeaderUserId("USR-3");
        update.setCollaboratorUserIds(List.of("USR-1"));
        var updated = service.replaceMembers(created.getProjectId(), update);
        verify(access).requireLeader(created.getProjectId());

        assertEquals("USR-3", updated.getLeader().getUserId());
        assertEquals(List.of("USR-1"), updated.getCollaborators().stream().map(member -> member.getUserId()).toList());
        assertEquals(2, members.size());
        assertEquals(1, members.stream().filter(member -> "LEADER".equals(member.getRole())).count());
        assertEquals("USR-3", members.stream().filter(member -> "LEADER".equals(member.getRole())).findFirst().orElseThrow().getUserId());
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
