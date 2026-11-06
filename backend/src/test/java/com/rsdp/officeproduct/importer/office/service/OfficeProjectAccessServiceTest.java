package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignFloor;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import com.rsdp.officeproduct.importer.office.entity.OfficeProjectMember;
import com.rsdp.officeproduct.importer.office.entity.OfficeWorkspace;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignFloorMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignProjectMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProjectMemberMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OfficeProjectAccessServiceTest {
    private final List<OfficeProjectMember> members = new ArrayList<>();
    private final Map<String, OfficeDesignFloor> floors = new LinkedHashMap<>();
    private final Map<String, OfficeDesignProject> projects = new LinkedHashMap<>();
    private final Map<String, OfficeWorkspace> workspaces = new LinkedHashMap<>();
    private CurrentUserService currentUser;
    private OfficeProjectAccessService access;

    @BeforeEach void setup() {
        members.clear();
        floors.clear();
        projects.clear();
        workspaces.clear();
        currentUser = mock(CurrentUserService.class);
        var memberMapper = mock(OfficeProjectMemberMapper.class);
        var floorMapper = mock(OfficeDesignFloorMapper.class);
        var projectMapper = mock(OfficeDesignProjectMapper.class);
        var workspaceMapper = mock(OfficeWorkspaceMapper.class);
        when(memberMapper.selectCount(any())).thenAnswer(call -> {
            List<String> params = params(call.getArgument(0));
            return members.stream().filter(member -> params.contains(member.getProjectId())
                && params.contains(member.getUserId())
                && (params.stream().noneMatch(role -> role.equals("LEADER") || role.equals("COLLABORATOR"))
                    || params.contains(member.getRole()))).count();
        });
        when(memberMapper.selectList(any())).thenAnswer(call -> {
            List<String> params = params(call.getArgument(0));
            return members.stream().filter(member -> params.contains(member.getUserId())).toList();
        });
        when(floorMapper.selectById(anyString())).thenAnswer(call -> floors.get(call.getArgument(0)));
        when(projectMapper.selectById(anyString())).thenAnswer(call -> projects.get(call.getArgument(0)));
        when(projectMapper.selectBatchIds(any())).thenAnswer(call -> {
            Collection<?> ids = call.getArgument(0);
            return projects.values().stream().filter(project -> ids.contains(project.getProjectId())).toList();
        });
        when(projectMapper.selectList(any())).thenAnswer(call -> {
            List<String> params = params(call.getArgument(0));
            return projects.values().stream()
                .filter(project -> params.isEmpty() || params.contains(project.getWorkspaceId())).toList();
        });
        when(workspaceMapper.selectById(anyString())).thenAnswer(call -> workspaces.get(call.getArgument(0)));
        when(workspaceMapper.selectOne(any())).thenAnswer(call -> {
            List<String> params = params(call.getArgument(0));
            return workspaces.values().stream().filter(workspace -> params.contains(workspace.getOwnerUserId()))
                .findFirst().orElse(null);
        });
        access = new OfficeProjectAccessService(currentUser, memberMapper, floorMapper, projectMapper, workspaceMapper);
    }

    private void enforcementOn(String userId) {
        enforcementOn(userId, null);
    }

    private void enforcementOn(String userId, String workspaceId) {
        when(currentUser.authEnabled()).thenReturn(true);
        OfficeUser user = new OfficeUser();
        user.setUserId(userId);
        user.setWorkspaceId(workspaceId);
        when(currentUser.currentUser()).thenReturn(user);
    }

    private void addMember(String projectId, String userId, String role) {
        OfficeProjectMember member = new OfficeProjectMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setRole(role);
        members.add(member);
    }

    private void addFloor(String floorId, String projectId) {
        OfficeDesignFloor floor = new OfficeDesignFloor();
        floor.setFloorId(floorId);
        floor.setProjectId(projectId);
        floors.put(floorId, floor);
    }

    private void addProject(String projectId, String workspaceId) {
        OfficeDesignProject project = new OfficeDesignProject();
        project.setProjectId(projectId);
        project.setWorkspaceId(workspaceId);
        projects.put(projectId, project);
    }

    private void addWorkspace(String workspaceId, String ownerUserId) {
        OfficeWorkspace workspace = new OfficeWorkspace();
        workspace.setWorkspaceId(workspaceId);
        workspace.setOwnerUserId(ownerUserId);
        workspaces.put(workspaceId, workspace);
    }

    @Test void devModeSkipsAllChecks() {
        when(currentUser.authEnabled()).thenReturn(false);
        assertDoesNotThrow(() -> access.requireMember("P1"));
        assertDoesNotThrow(() -> access.requireLeader("P1"));
        assertDoesNotThrow(() -> access.requireFloorAccess("UNKNOWN"));
        assertNull(access.accessibleProjectIds());
        assertNull(access.currentUserId());
    }

    @Test void memberAndLeaderChecksFollowMembership() {
        addProject("P1", null);
        enforcementOn("USR-1");
        addMember("P1", "USR-1", "LEADER");
        addMember("P1", "USR-2", "COLLABORATOR");
        assertDoesNotThrow(() -> access.requireMember("P1"));
        assertDoesNotThrow(() -> access.requireLeader("P1"));

        enforcementOn("USR-2");
        assertDoesNotThrow(() -> access.requireMember("P1"));
        assertEquals("只有项目负责人或经销商主号可以管理成员", assertThrows(AccessDeniedException.class,
            () -> access.requireLeader("P1")).getMessage());

        enforcementOn("USR-9");
        assertEquals("无权访问该项目", assertThrows(AccessDeniedException.class,
            () -> access.requireMember("P1")).getMessage());
    }

    @Test void floorAccessResolvesOwningProjectBeforeMembershipCheck() {
        addProject("P1", null);
        addProject("P2", null);
        enforcementOn("USR-1");
        addMember("P1", "USR-1", "COLLABORATOR");
        addFloor("F1", "P1");
        addFloor("F2", "P2");
        assertDoesNotThrow(() -> access.requireFloorAccess("F1"));
        assertThrows(AccessDeniedException.class, () -> access.requireFloorAccess("F2"));
        assertEquals("楼层不存在，请先创建项目和楼层", assertThrows(BusinessException.class,
            () -> access.requireFloorAccess("UNKNOWN")).getMessage());
    }

    @Test void unknownProjectRejectedAsMissing() {
        enforcementOn("USR-1");
        assertEquals("项目不存在", assertThrows(BusinessException.class,
            () -> access.requireMember("UNKNOWN")).getMessage());
    }

    @Test void crossWorkspaceMemberDeniedEvenWithMembership() {
        addWorkspace("W1", "USR-D1");
        addWorkspace("W2", "USR-D2");
        addProject("P1", "W2");
        addMember("P1", "USR-1", "COLLABORATOR");
        // USR-1 账号归属 W1，却是 W2 项目的成员（异常数据）：写入端拒绝之外，读取端同样拦截。
        enforcementOn("USR-1", "W1");
        assertThrows(AccessDeniedException.class, () -> access.requireMember("P1"));
        assertThrows(AccessDeniedException.class, () -> access.requireLeader("P1"));
    }

    @Test void sameWorkspaceMemberAllowed() {
        addWorkspace("W1", "USR-D1");
        addProject("P1", "W1");
        addMember("P1", "USR-1", "LEADER");
        addMember("P1", "USR-2", "COLLABORATOR");
        enforcementOn("USR-2", "W1");
        assertDoesNotThrow(() -> access.requireMember("P1"));
        assertThrows(AccessDeniedException.class, () -> access.requireLeader("P1"));
        enforcementOn("USR-1", "W1");
        assertDoesNotThrow(() -> access.requireLeader("P1"));
    }

    @Test void dealerOwnerAccessesAndManagesWorkspaceProjectsWithoutMembership() {
        addWorkspace("W1", "USR-D1");
        addProject("P1", "W1");
        addMember("P1", "USR-1", "LEADER");
        enforcementOn("USR-D1", "W1");
        assertDoesNotThrow(() -> access.requireMember("P1"));
        assertDoesNotThrow(() -> access.requireLeader("P1"));
    }

    @Test void dealerOwnerCannotTouchOtherWorkspaceProjects() {
        addWorkspace("W1", "USR-D1");
        addWorkspace("W2", "USR-D2");
        addProject("P2", "W2");
        enforcementOn("USR-D1", "W1");
        assertThrows(AccessDeniedException.class, () -> access.requireMember("P2"));
        assertThrows(AccessDeniedException.class, () -> access.requireLeader("P2"));
    }

    @Test void accessibleProjectsComeFromMembership() {
        addProject("P1", null);
        addProject("P2", null);
        addProject("P3", null);
        enforcementOn("USR-1");
        addMember("P1", "USR-1", "LEADER");
        addMember("P2", "USR-1", "COLLABORATOR");
        addMember("P3", "USR-2", "LEADER");
        assertEquals(Set.of("P1", "P2"), access.accessibleProjectIds());
    }

    @Test void accessibleProjectsFilterCrossWorkspaceAndIncludeDealerWorkspace() {
        addWorkspace("W1", "USR-D1");
        addWorkspace("W2", "USR-D2");
        addProject("P1", "W1");
        addProject("P2", "W1");
        addProject("P3", "W2");
        addMember("P1", "USR-1", "COLLABORATOR");
        addMember("P3", "USR-1", "COLLABORATOR"); // 异常跨工作区成员关系，不应可见
        enforcementOn("USR-1", "W1");
        assertEquals(Set.of("P1"), access.accessibleProjectIds());
        // 经销商主号可见本工作区全部项目，无论是否成员。
        enforcementOn("USR-D1", "W1");
        assertEquals(Set.of("P1", "P2"), access.accessibleProjectIds());
    }

    private List<String> params(QueryWrapper<?> query) {
        query.getSqlSegment();
        return query.getParamNameValuePairs().values().stream().flatMap(value -> {
            if (value instanceof Collection<?> values) return values.stream();
            return Stream.of(value);
        }).filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
