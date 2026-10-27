package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.dto.CreateOfficeFloorRequest;
import com.rsdp.officeproduct.importer.office.dto.CreateOfficeProjectRequest;
import com.rsdp.officeproduct.importer.office.dto.OfficeFloorDto;
import com.rsdp.officeproduct.importer.office.dto.OfficeProjectDto;
import com.rsdp.officeproduct.importer.office.dto.UpdateOfficeProjectRequest;
import com.rsdp.officeproduct.importer.office.dto.UpdateOfficeRequirementsRequest;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignFloor;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignFloorMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignProjectMapper;
import com.rsdp.officeproduct.importer.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OfficeProjectService {

    private final OfficeDesignProjectMapper projectMapper;
    private final OfficeDesignFloorMapper floorMapper;

    public List<OfficeProjectDto> listProjects() {
        List<OfficeDesignProject> projects = projectMapper.selectList(
            new QueryWrapper<OfficeDesignProject>().orderByAsc("created_at").orderByAsc("project_id"));
        if (projects.isEmpty()) {
            return List.of();
        }
        List<String> projectIds = projects.stream().map(OfficeDesignProject::getProjectId).toList();
        List<OfficeDesignFloor> floors = floorMapper.selectList(new QueryWrapper<OfficeDesignFloor>()
            .in("project_id", projectIds).orderByAsc("sort_order").orderByAsc("created_at").orderByAsc("floor_id"));
        Map<String, List<OfficeDesignFloor>> floorsByProject = floors.stream()
            .collect(Collectors.groupingBy(OfficeDesignFloor::getProjectId));
        return projects.stream()
            .map(project -> toProjectDto(project, floorsByProject.getOrDefault(project.getProjectId(), List.of())))
            .toList();
    }

    @Transactional
    public OfficeProjectDto createProject(CreateOfficeProjectRequest request) {
        if (request == null) {
            throw new BusinessException("项目参数不能为空");
        }
        String projectName = requiredName(request.getName(), "项目名称");
        if (request.getFloors() == null || request.getFloors().isEmpty()) {
            throw new BusinessException("至少规划一个楼层");
        }
        List<String> floorNames = new ArrayList<>();
        Set<String> uniqueNames = new HashSet<>();
        for (CreateOfficeFloorRequest floor : request.getFloors()) {
            if (floor == null) {
                throw new BusinessException("楼层参数不能为空");
            }
            String floorName = requiredName(floor.getName(), "楼层名称");
            if (!uniqueNames.add(normalizeName(floorName))) {
                throw new BusinessException("楼层名称不能重复");
            }
            floorNames.add(floorName);
        }

        LocalDateTime now = LocalDateTime.now();
        OfficeDesignProject project = new OfficeDesignProject();
        project.setProjectId(IdGenerator.spatialId("OPRJ"));
        project.setName(projectName);
        project.setCustomerRequirements(request.getCustomerRequirements() == null ? "" : request.getCustomerRequirements());
        project.setRequirementsRevision(1);
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        projectMapper.insert(project);

        List<OfficeDesignFloor> floors = new ArrayList<>();
        for (int i = 0; i < floorNames.size(); i++) {
            OfficeDesignFloor floor = newFloor(project.getProjectId(), floorNames.get(i), i, now);
            floorMapper.insert(floor);
            floors.add(floor);
        }
        return toProjectDto(project, floors);
    }

    @Transactional
    public OfficeFloorDto addFloor(String projectId, CreateOfficeFloorRequest request) {
        requireProject(projectId);
        if (request == null) {
            throw new BusinessException("楼层参数不能为空");
        }
        String name = requiredName(request.getName(), "楼层名称");
        ensureFloorNameAvailable(projectId, name, null);
        LocalDateTime now = LocalDateTime.now();
        OfficeDesignFloor floor = newFloor(projectId, name, floorMapper.nextSortOrder(projectId), now);
        try {
            floorMapper.insert(floor);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("楼层名称不能重复");
        }
        return toFloorDto(floor);
    }

    @Transactional
    public OfficeProjectDto updateProject(String projectId, UpdateOfficeProjectRequest request) {
        OfficeDesignProject project = requireProject(projectId);
        if (request == null) {
            throw new BusinessException("项目参数不能为空");
        }
        project.setName(requiredName(request.getName(), "项目名称"));
        project.setUpdatedAt(LocalDateTime.now());
        projectMapper.updateById(project);
        return toProjectDto(project, floors(projectId));
    }

    @Transactional
    public OfficeFloorDto updateFloor(String projectId, String floorId, CreateOfficeFloorRequest request) {
        requireProject(projectId);
        OfficeDesignFloor floor = StringUtils.hasText(floorId) ? floorMapper.selectById(floorId) : null;
        if (floor == null || !projectId.equals(floor.getProjectId())) {
            throw new BusinessException("楼层不存在");
        }
        if (request == null) {
            throw new BusinessException("楼层参数不能为空");
        }
        String name = requiredName(request.getName(), "楼层名称");
        ensureFloorNameAvailable(projectId, name, floorId);
        floor.setName(name);
        floor.setUpdatedAt(LocalDateTime.now());
        try {
            floorMapper.updateById(floor);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("楼层名称不能重复");
        }
        return toFloorDto(floor);
    }

    @Transactional
    public OfficeProjectDto updateRequirements(String projectId, UpdateOfficeRequirementsRequest request) {
        OfficeDesignProject project = requireProject(projectId);
        if (request == null || request.getCustomerRequirements() == null) {
            throw new BusinessException("客户需求不能为空");
        }
        project.setCustomerRequirements(request.getCustomerRequirements());
        project.setRequirementsRevision(project.getRequirementsRevision() + 1);
        project.setUpdatedAt(LocalDateTime.now());
        projectMapper.updateById(project);
        return toProjectDto(project, floors(projectId));
    }

    public void requireFloor(String floorId) {
        if (!StringUtils.hasText(floorId) || floorMapper.selectById(floorId) == null) {
            throw new BusinessException("楼层不存在，请先创建项目和楼层");
        }
    }

    private OfficeDesignProject requireProject(String projectId) {
        OfficeDesignProject project = StringUtils.hasText(projectId) ? projectMapper.selectById(projectId) : null;
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        return project;
    }

    private void ensureFloorNameAvailable(String projectId, String name, String excludedFloorId) {
        List<OfficeDesignFloor> floors = floors(projectId);
        boolean duplicate = floors.stream().anyMatch(floor -> !floor.getFloorId().equals(excludedFloorId)
            && normalizeName(floor.getName()).equals(normalizeName(name)));
        if (duplicate) {
            throw new BusinessException("楼层名称不能重复");
        }
    }

    private List<OfficeDesignFloor> floors(String projectId) {
        return floorMapper.selectList(new QueryWrapper<OfficeDesignFloor>().eq("project_id", projectId)
            .orderByAsc("sort_order").orderByAsc("created_at").orderByAsc("floor_id"));
    }

    private OfficeDesignFloor newFloor(String projectId, String name, int sortOrder, LocalDateTime now) {
        OfficeDesignFloor floor = new OfficeDesignFloor();
        floor.setFloorId(IdGenerator.spatialId("OFLR"));
        floor.setProjectId(projectId);
        floor.setName(name);
        floor.setSortOrder(sortOrder);
        floor.setCreatedAt(now);
        floor.setUpdatedAt(now);
        return floor;
    }

    private String requiredName(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(label + "不能为空");
        }
        String name = value.trim();
        if (name.length() > 200) {
            throw new BusinessException(label + "不能超过 200 个字符");
        }
        return name;
    }

    private String normalizeName(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private OfficeProjectDto toProjectDto(OfficeDesignProject project, List<OfficeDesignFloor> floors) {
        OfficeProjectDto dto = new OfficeProjectDto();
        dto.setProjectId(project.getProjectId());
        dto.setName(project.getName());
        dto.setCustomerRequirements(project.getCustomerRequirements());
        dto.setRequirementsRevision(project.getRequirementsRevision());
        dto.setCreatedAt(project.getCreatedAt());
        dto.setUpdatedAt(project.getUpdatedAt());
        dto.setFloors(floors.stream().map(this::toFloorDto).toList());
        return dto;
    }

    private OfficeFloorDto toFloorDto(OfficeDesignFloor floor) {
        OfficeFloorDto dto = new OfficeFloorDto();
        dto.setFloorId(floor.getFloorId());
        dto.setProjectId(floor.getProjectId());
        dto.setName(floor.getName());
        dto.setSortOrder(floor.getSortOrder());
        dto.setCreatedAt(floor.getCreatedAt());
        dto.setUpdatedAt(floor.getUpdatedAt());
        return dto;
    }
}
