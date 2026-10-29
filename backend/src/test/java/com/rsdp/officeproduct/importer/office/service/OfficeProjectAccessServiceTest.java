package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignFloor;
import com.rsdp.officeproduct.importer.office.entity.OfficeProjectMember;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignFloorMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProjectMemberMapper;
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
    private CurrentUserService currentUser;
    private OfficeProjectAccessService access;

    @BeforeEach void setup() {
        members.clear();
        floors.clear();
        currentUser = mock(CurrentUserService.class);
        var memberMapper = mock(OfficeProjectMemberMapper.class);
        var floorMapper = mock(OfficeDesignFloorMapper.class);
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
        access = new OfficeProjectAccessService(currentUser, memberMapper, floorMapper);
    }

    private void enforcementOn(String userId) {
        when(currentUser.authEnabled()).thenReturn(true);
        OfficeUser user = new OfficeUser();
        user.setUserId(userId);
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

    @Test void devModeSkipsAllChecks() {
        when(currentUser.authEnabled()).thenReturn(false);
        assertDoesNotThrow(() -> access.requireMember("P1"));
        assertDoesNotThrow(() -> access.requireLeader("P1"));
        assertDoesNotThrow(() -> access.requireFloorAccess("UNKNOWN"));
        assertNull(access.accessibleProjectIds());
        assertNull(access.currentUserId());
    }

    @Test void memberAndLeaderChecksFollowMembership() {
        enforcementOn("USR-1");
        addMember("P1", "USR-1", "LEADER");
        addMember("P1", "USR-2", "COLLABORATOR");
        assertDoesNotThrow(() -> access.requireMember("P1"));
        assertDoesNotThrow(() -> access.requireLeader("P1"));

        enforcementOn("USR-2");
        assertDoesNotThrow(() -> access.requireMember("P1"));
        assertEquals("只有项目负责人可以管理成员", assertThrows(AccessDeniedException.class,
            () -> access.requireLeader("P1")).getMessage());

        enforcementOn("USR-9");
        assertEquals("无权访问该项目", assertThrows(AccessDeniedException.class,
            () -> access.requireMember("P1")).getMessage());
    }

    @Test void floorAccessResolvesOwningProjectBeforeMembershipCheck() {
        enforcementOn("USR-1");
        addMember("P1", "USR-1", "COLLABORATOR");
        addFloor("F1", "P1");
        addFloor("F2", "P2");
        assertDoesNotThrow(() -> access.requireFloorAccess("F1"));
        assertThrows(AccessDeniedException.class, () -> access.requireFloorAccess("F2"));
        assertEquals("楼层不存在，请先创建项目和楼层", assertThrows(BusinessException.class,
            () -> access.requireFloorAccess("UNKNOWN")).getMessage());
    }

    @Test void accessibleProjectsComeFromMembership() {
        enforcementOn("USR-1");
        addMember("P1", "USR-1", "LEADER");
        addMember("P2", "USR-1", "COLLABORATOR");
        addMember("P3", "USR-2", "LEADER");
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
