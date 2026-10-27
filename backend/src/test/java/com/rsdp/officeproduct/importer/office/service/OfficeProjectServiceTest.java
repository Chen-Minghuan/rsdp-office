package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.dto.CreateOfficeFloorRequest;
import com.rsdp.officeproduct.importer.office.dto.CreateOfficeProjectRequest;
import com.rsdp.officeproduct.importer.office.dto.UpdateOfficeProjectRequest;
import com.rsdp.officeproduct.importer.office.dto.UpdateOfficeRequirementsRequest;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignFloor;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignFloorMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignProjectMapper;
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
    private OfficeProjectService service;

    @BeforeEach void setup() {
        projects.clear();
        floors.clear();
        var projectMapper = mock(OfficeDesignProjectMapper.class);
        var floorMapper = mock(OfficeDesignFloorMapper.class);
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
        service = new OfficeProjectService(projectMapper, floorMapper);
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
