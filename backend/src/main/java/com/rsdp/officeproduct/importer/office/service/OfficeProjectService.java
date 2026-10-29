package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.mapper.OfficeUserMapper;
import com.rsdp.officeproduct.importer.office.dto.AssignableUserDto;
import com.rsdp.officeproduct.importer.office.dto.CreateOfficeFloorRequest;
import com.rsdp.officeproduct.importer.office.dto.CreateOfficeProjectRequest;
import com.rsdp.officeproduct.importer.office.dto.OfficeFloorDto;
import com.rsdp.officeproduct.importer.office.dto.OfficeProjectDto;
import com.rsdp.officeproduct.importer.office.dto.ProjectMemberDto;
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
import com.rsdp.officeproduct.importer.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfficeProjectService {

    private static final int MAX_REFERENCE_IMAGES = 10;
    private static final String REFERENCE_URL_PREFIX = "/uploads/images/";
    private static final int ASSIGNABLE_USER_LIMIT = 20;

    private final OfficeDesignProjectMapper projectMapper;
    private final OfficeDesignFloorMapper floorMapper;
    private final OfficeProjectMemberMapper memberMapper;
    private final OfficeUserMapper userMapper;
    private final OfficeProjectAccessService accessService;
    private final ObjectMapper objectMapper;

    public List<OfficeProjectDto> listProjects() {
        List<OfficeDesignProject> projects = projectMapper.selectList(
            new QueryWrapper<OfficeDesignProject>().orderByAsc("created_at").orderByAsc("project_id"));
        Set<String> accessible = accessService.accessibleProjectIds();
        if (accessible != null) {
            projects = projects.stream().filter(project -> accessible.contains(project.getProjectId())).toList();
        }
        if (projects.isEmpty()) {
            return List.of();
        }
        List<String> projectIds = projects.stream().map(OfficeDesignProject::getProjectId).toList();
        List<OfficeDesignFloor> floors = floorMapper.selectList(new QueryWrapper<OfficeDesignFloor>()
            .in("project_id", projectIds).orderByAsc("sort_order").orderByAsc("created_at").orderByAsc("floor_id"));
        Map<String, List<OfficeDesignFloor>> floorsByProject = floors.stream()
            .collect(Collectors.groupingBy(OfficeDesignFloor::getProjectId));
        Map<String, List<OfficeProjectMember>> membersByProject = membersByProject(projectIds);
        Map<String, OfficeUser> usersById = usersById(membersByProject);
        return projects.stream()
            .map(project -> toProjectDto(project,
                floorsByProject.getOrDefault(project.getProjectId(), List.of()),
                membersByProject.getOrDefault(project.getProjectId(), List.of()),
                usersById))
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
        List<ProjectReferenceImage> referenceImages = validateReferenceImages(request.getReferenceImages());
        boolean membersProvided = StringUtils.hasText(request.getLeaderUserId())
            || (request.getCollaboratorUserIds() != null && !request.getCollaboratorUserIds().isEmpty());
        List<String> collaboratorIds = List.of();
        String leaderUserId = null;
        if (membersProvided || accessService.enforcementEnabled()) {
            leaderUserId = request.getLeaderUserId();
            collaboratorIds = validateMemberSet(leaderUserId, request.getCollaboratorUserIds());
        }

        LocalDateTime now = LocalDateTime.now();
        OfficeDesignProject project = new OfficeDesignProject();
        project.setProjectId(IdGenerator.spatialId("OPRJ"));
        project.setName(projectName);
        project.setCustomerRequirements(request.getCustomerRequirements() == null ? "" : request.getCustomerRequirements());
        project.setRequirementsRevision(1);
        project.setReferenceImages(writeReferenceImages(referenceImages));
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        projectMapper.insert(project);

        List<OfficeDesignFloor> floors = new ArrayList<>();
        for (int i = 0; i < floorNames.size(); i++) {
            OfficeDesignFloor floor = newFloor(project.getProjectId(), floorNames.get(i), i, now);
            floorMapper.insert(floor);
            floors.add(floor);
        }
        List<OfficeProjectMember> members = new ArrayList<>();
        if (leaderUserId != null) {
            members = insertMembers(project.getProjectId(), leaderUserId, collaboratorIds, now);
        }
        return toProjectDto(project, floors, members, usersById(members));
    }

    @Transactional
    public OfficeFloorDto addFloor(String projectId, CreateOfficeFloorRequest request) {
        accessService.requireMember(projectId);
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
        accessService.requireMember(projectId);
        OfficeDesignProject project = requireProject(projectId);
        if (request == null) {
            throw new BusinessException("项目参数不能为空");
        }
        project.setName(requiredName(request.getName(), "项目名称"));
        project.setUpdatedAt(LocalDateTime.now());
        projectMapper.updateById(project);
        return toProjectDto(project, floors(projectId), members(projectId), usersById(members(projectId)));
    }

    @Transactional
    public OfficeFloorDto updateFloor(String projectId, String floorId, CreateOfficeFloorRequest request) {
        accessService.requireMember(projectId);
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
        accessService.requireMember(projectId);
        OfficeDesignProject project = requireProject(projectId);
        if (request == null || request.getCustomerRequirements() == null) {
            throw new BusinessException("客户需求不能为空");
        }
        project.setCustomerRequirements(request.getCustomerRequirements());
        project.setRequirementsRevision(project.getRequirementsRevision() + 1);
        project.setUpdatedAt(LocalDateTime.now());
        projectMapper.updateById(project);
        return toProjectDto(project, floors(projectId), members(projectId), usersById(members(projectId)));
    }

    public List<ProjectMemberDto> listMembers(String projectId) {
        accessService.requireMember(projectId);
        requireProject(projectId);
        List<OfficeProjectMember> members = members(projectId);
        Map<String, OfficeUser> usersById = usersById(members);
        return members.stream().map(member -> toMemberDto(member, usersById.get(member.getUserId()))).toList();
    }

    /**
     * 原子替换负责人与协作者集合：同事务先删后写，依赖
     * uk_office_project_one_leader 偏索引兜底并发双负责人。
     */
    @Transactional
    public OfficeProjectDto replaceMembers(String projectId, UpdateProjectMembersRequest request) {
        accessService.requireLeader(projectId);
        requireProject(projectId);
        if (request == null) {
            throw new BusinessException("成员参数不能为空");
        }
        List<String> collaboratorIds = validateMemberSet(request.getLeaderUserId(), request.getCollaboratorUserIds());
        memberMapper.delete(new QueryWrapper<OfficeProjectMember>().eq("project_id", projectId));
        List<OfficeProjectMember> members = insertMembers(projectId, request.getLeaderUserId().trim(), collaboratorIds, LocalDateTime.now());
        return toProjectDto(requireProject(projectId), floors(projectId), members, usersById(members));
    }

    @Transactional
    public OfficeProjectDto updateReferences(String projectId, UpdateProjectReferencesRequest request) {
        accessService.requireMember(projectId);
        OfficeDesignProject project = requireProject(projectId);
        if (request == null) {
            throw new BusinessException("参考图片参数不能为空");
        }
        List<ProjectReferenceImage> images = validateReferenceImages(request.getReferenceImages());
        project.setReferenceImages(writeReferenceImages(images));
        project.setUpdatedAt(LocalDateTime.now());
        projectMapper.updateById(project);
        return toProjectDto(project, floors(projectId), members(projectId), usersById(members(projectId)));
    }

    /** 搜索可作为项目成员的有效用户，仅返回有限字段。 */
    public List<AssignableUserDto> listAssignableUsers(String keyword) {
        if (accessService.enforcementEnabled()) {
            accessService.currentUserId();
        }
        QueryWrapper<OfficeUser> query = new QueryWrapper<OfficeUser>().eq("status", "active");
        if (StringUtils.hasText(keyword)) {
            String like = keyword.trim();
            query.and(wrapper -> wrapper.like("username", like).or().like("display_name", like));
        }
        query.orderByAsc("username").last("limit " + ASSIGNABLE_USER_LIMIT);
        return userMapper.selectList(query).stream().map(user -> {
            AssignableUserDto dto = new AssignableUserDto();
            dto.setUserId(user.getUserId());
            dto.setUsername(user.getUsername());
            dto.setDisplayName(user.getDisplayName());
            return dto;
        }).toList();
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

    /**
     * 校验负责人/协作者集合并返回去重后的协作者 ID。
     * 负责人必填且为有效用户；协作者不得重复、不得与负责人重合、必须为有效用户。
     */
    private List<String> validateMemberSet(String leaderUserId, List<String> collaboratorUserIds) {
        if (!StringUtils.hasText(leaderUserId)) {
            throw new BusinessException("项目负责人不能为空");
        }
        requireActiveUser(leaderUserId.trim(), "项目负责人不存在或已停用");
        List<String> collaboratorIds = collaboratorUserIds == null ? List.of()
            : collaboratorUserIds.stream().filter(StringUtils::hasText).map(String::trim).toList();
        Set<String> unique = new LinkedHashSet<>(collaboratorIds);
        if (unique.size() != collaboratorIds.size()) {
            throw new BusinessException("协作者不能重复");
        }
        if (unique.contains(leaderUserId.trim())) {
            throw new BusinessException("负责人不能同时是协作者");
        }
        for (String userId : unique) {
            requireActiveUser(userId, "协作者不存在或已停用");
        }
        return List.copyOf(unique);
    }

    private void requireActiveUser(String userId, String message) {
        OfficeUser user = userMapper.selectById(userId);
        if (user == null || !"active".equalsIgnoreCase(user.getStatus())) {
            throw new BusinessException(message);
        }
    }

    private List<OfficeProjectMember> insertMembers(String projectId, String leaderUserId,
                                                    List<String> collaboratorIds, LocalDateTime now) {
        List<OfficeProjectMember> members = new ArrayList<>();
        OfficeProjectMember leader = newMember(projectId, leaderUserId, OfficeProjectMember.ROLE_LEADER, now);
        try {
            memberMapper.insert(leader);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("项目负责人冲突，请刷新后重试");
        }
        members.add(leader);
        for (String userId : collaboratorIds) {
            OfficeProjectMember collaborator = newMember(projectId, userId, OfficeProjectMember.ROLE_COLLABORATOR, now);
            memberMapper.insert(collaborator);
            members.add(collaborator);
        }
        return members;
    }

    private OfficeProjectMember newMember(String projectId, String userId, String role, LocalDateTime now) {
        OfficeProjectMember member = new OfficeProjectMember();
        member.setProjectId(projectId);
        member.setUserId(userId);
        member.setRole(role);
        member.setCreatedAt(now);
        return member;
    }

    private List<ProjectReferenceImage> validateReferenceImages(List<ProjectReferenceImage> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        if (images.size() > MAX_REFERENCE_IMAGES) {
            throw new BusinessException("项目参考图片不能超过 " + MAX_REFERENCE_IMAGES + " 张");
        }
        List<ProjectReferenceImage> validated = new ArrayList<>();
        for (ProjectReferenceImage image : images) {
            if (image == null || !StringUtils.hasText(image.getUrl())) {
                throw new BusinessException("参考图片地址不能为空");
            }
            String url = image.getUrl().trim();
            if (!url.startsWith(REFERENCE_URL_PREFIX) || url.contains("..")) {
                throw new BusinessException("参考图片地址无效，仅支持本站上传的图片");
            }
            ProjectReferenceImage item = new ProjectReferenceImage();
            item.setUrl(url);
            String name = image.getName() == null ? "" : image.getName().trim();
            if (name.length() > 200) {
                throw new BusinessException("参考图片名称不能超过 200 个字符");
            }
            item.setName(name);
            validated.add(item);
        }
        return validated;
    }

    private String writeReferenceImages(List<ProjectReferenceImage> images) {
        try {
            return objectMapper.writeValueAsString(images == null ? List.of() : images);
        } catch (Exception e) {
            throw new BusinessException("参考图片数据无法保存", e);
        }
    }

    private List<ProjectReferenceImage> readReferenceImages(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ProjectReferenceImage>>() {});
        } catch (Exception e) {
            log.warn("项目参考图片数据无法解析，按空列表返回: {}", e.getMessage());
            return List.of();
        }
    }

    private List<OfficeProjectMember> members(String projectId) {
        return memberMapper.selectList(new QueryWrapper<OfficeProjectMember>()
            .eq("project_id", projectId).orderByAsc("created_at").orderByAsc("user_id"));
    }

    private Map<String, List<OfficeProjectMember>> membersByProject(List<String> projectIds) {
        if (projectIds.isEmpty()) {
            return Map.of();
        }
        return memberMapper.selectList(new QueryWrapper<OfficeProjectMember>()
                .in("project_id", projectIds).orderByAsc("created_at").orderByAsc("user_id"))
            .stream().collect(Collectors.groupingBy(OfficeProjectMember::getProjectId));
    }

    private Map<String, OfficeUser> usersById(Map<String, List<OfficeProjectMember>> membersByProject) {
        Set<String> userIds = membersByProject.values().stream().flatMap(List::stream)
            .map(OfficeProjectMember::getUserId).collect(Collectors.toSet());
        return usersById(userIds);
    }

    private Map<String, OfficeUser> usersById(List<OfficeProjectMember> members) {
        return usersById(members.stream().map(OfficeProjectMember::getUserId).collect(Collectors.toSet()));
    }

    private Map<String, OfficeUser> usersById(Set<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(userIds).stream()
            .collect(Collectors.toMap(OfficeUser::getUserId, Function.identity()));
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

    private OfficeProjectDto toProjectDto(OfficeDesignProject project, List<OfficeDesignFloor> floors,
                                          List<OfficeProjectMember> members, Map<String, OfficeUser> usersById) {
        OfficeProjectDto dto = new OfficeProjectDto();
        dto.setProjectId(project.getProjectId());
        dto.setName(project.getName());
        dto.setCustomerRequirements(project.getCustomerRequirements());
        dto.setRequirementsRevision(project.getRequirementsRevision());
        dto.setReferenceImages(readReferenceImages(project.getReferenceImages()));
        List<OfficeProjectMember> ordered = members.stream()
            .sorted(Comparator.comparing((OfficeProjectMember member) -> !OfficeProjectMember.ROLE_LEADER.equals(member.getRole())))
            .toList();
        for (OfficeProjectMember member : ordered) {
            if (OfficeProjectMember.ROLE_LEADER.equals(member.getRole()) && dto.getLeader() == null) {
                dto.setLeader(toMemberDto(member, usersById.get(member.getUserId())));
            }
        }
        dto.setCollaborators(ordered.stream()
            .filter(member -> OfficeProjectMember.ROLE_COLLABORATOR.equals(member.getRole()))
            .map(member -> toMemberDto(member, usersById.get(member.getUserId())))
            .toList());
        dto.setCreatedAt(project.getCreatedAt());
        dto.setUpdatedAt(project.getUpdatedAt());
        dto.setFloors(floors.stream().map(this::toFloorDto).toList());
        return dto;
    }

    private ProjectMemberDto toMemberDto(OfficeProjectMember member, OfficeUser user) {
        ProjectMemberDto dto = new ProjectMemberDto();
        dto.setUserId(member.getUserId());
        dto.setUsername(user != null ? user.getUsername() : "");
        dto.setDisplayName(user != null ? user.getDisplayName() : "");
        dto.setRole(member.getRole());
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
