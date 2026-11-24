package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.mapper.OfficeUserMapper;
import com.rsdp.officeproduct.importer.service.RsdpCatalogGateway;
import com.rsdp.officeproduct.importer.office.dto.AssignableUserDto;
import com.rsdp.officeproduct.importer.office.dto.CreateOfficeFloorRequest;
import com.rsdp.officeproduct.importer.office.dto.CreateOfficeProjectRequest;
import com.rsdp.officeproduct.importer.office.dto.OfficeFloorDto;
import com.rsdp.officeproduct.importer.office.dto.OfficeProjectBudgetDto;
import com.rsdp.officeproduct.importer.office.dto.OfficeProjectDto;
import com.rsdp.officeproduct.importer.office.dto.ProjectMemberDto;
import com.rsdp.officeproduct.importer.office.dto.ProjectReferenceImage;
import com.rsdp.officeproduct.importer.office.dto.UpdateOfficeProjectRequest;
import com.rsdp.officeproduct.importer.office.dto.UpdateOfficeRequirementsRequest;
import com.rsdp.officeproduct.importer.office.dto.UpdateProjectMembersRequest;
import com.rsdp.officeproduct.importer.office.dto.UpdateProjectReferencesRequest;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignFloor;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import com.rsdp.officeproduct.importer.office.entity.OfficeCart;
import com.rsdp.officeproduct.importer.office.entity.OfficeProjectMember;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignFloorMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignProjectMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCartMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProjectMemberMapper;
import com.rsdp.officeproduct.importer.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private final OfficeAuditService auditService;
    private final ObjectMapper objectMapper;
    private final OfficeCartMapper cartMapper;
    private final RsdpCatalogGateway catalogGateway;
    private final SharedCatalogService sharedCatalogService;

    public List<OfficeProjectDto> listProjects() {
        return listProjects(true);
    }

    public List<OfficeProjectDto> listProjects(boolean includeBudget) {
        List<OfficeDesignProject> projects = visibleProjects();
        if (projects.isEmpty()) return List.of();

        List<String> projectIds = projects.stream().map(OfficeDesignProject::getProjectId).toList();
        List<OfficeDesignFloor> floors = floorsByProjectIds(projectIds);
        Map<String, List<OfficeDesignFloor>> floorsByProject = floors.stream()
            .collect(Collectors.groupingBy(OfficeDesignFloor::getProjectId));
        Map<String, List<OfficeProjectMember>> membersByProject = membersByProject(projectIds);
        Map<String, OfficeUser> usersById = usersById(membersByProject);
        Map<String, ProjectBudget> budgets = includeBudget ? budgetsByProject(projectIds, floors) : Map.of();
        return projects.stream()
            .map(project -> {
                OfficeProjectDto dto = toProjectDto(project,
                    floorsByProject.getOrDefault(project.getProjectId(), List.of()),
                    membersByProject.getOrDefault(project.getProjectId(), List.of()), usersById);
                ProjectBudget budget = budgets.get(project.getProjectId());
                if (budget != null) {
                    dto.setBudgetTotal(budget.total());
                    dto.setBudgetStatus(budget.status());
                }
                return dto;
            })
            .toList();
    }

    public Map<String, OfficeProjectBudgetDto> listProjectBudgets() {
        List<String> projectIds = visibleProjects().stream().map(OfficeDesignProject::getProjectId).toList();
        if (projectIds.isEmpty()) return Map.of();

        return budgetsByProject(projectIds, floorsByProjectIds(projectIds)).entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey,
                entry -> new OfficeProjectBudgetDto(entry.getValue().total(), entry.getValue().status())));
    }

    private List<OfficeDesignProject> visibleProjects() {
        Set<String> accessible = accessService.accessibleProjectIds();
        if (accessible != null && accessible.isEmpty()) return List.of();

        List<OfficeDesignProject> projects = projectMapper.selectList(new QueryWrapper<OfficeDesignProject>()
            .in(accessible != null, "project_id", accessible).orderByAsc("created_at").orderByAsc("project_id"));
        return accessible == null ? projects : projects.stream()
            .filter(project -> accessible.contains(project.getProjectId())).toList();
    }

    private List<OfficeDesignFloor> floorsByProjectIds(List<String> projectIds) {
        return floorMapper.selectList(new QueryWrapper<OfficeDesignFloor>()
            .in("project_id", projectIds).orderByAsc("sort_order").orderByAsc("created_at").orderByAsc("floor_id"));
    }

    private Map<String, ProjectBudget> budgetsByProject(List<String> projectIds, List<OfficeDesignFloor> floors) {
        Map<String, ProjectBudget> budgets = new HashMap<>();
        projectIds.forEach(projectId -> budgets.put(projectId, ProjectBudget.complete()));
        if (floors.isEmpty()) {
            return budgets;
        }

        Map<String, String> projectIdByFloorId = floors.stream()
            .collect(Collectors.toMap(OfficeDesignFloor::getFloorId, OfficeDesignFloor::getProjectId));
        List<OfficeCart> carts = cartMapper.selectList(new QueryWrapper<OfficeCart>().in("project_id", projectIdByFloorId.keySet()));
        if (carts.isEmpty()) {
            return budgets;
        }

        Set<String> projectIdsWithCart = carts.stream()
            .map(cart -> projectIdByFloorId.get(cart.getProjectId()))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        try {
            Set<String> usedVariantIds = carts.stream()
                .filter(cart -> cart.getQty() != null && cart.getQty() > 0)
                .map(OfficeCart::getProductId).filter(StringUtils::hasText).collect(Collectors.toSet());
            Map<String, BigDecimal> priceByVariantId = catalogGateway.retailPrices(sharedCatalogService.resolveViewMode(), usedVariantIds);

            for (OfficeCart cart : carts) {
                String projectId = projectIdByFloorId.get(cart.getProjectId());
                if (projectId == null || cart.getQty() == null || cart.getQty() <= 0) {
                    continue;
                }
                BigDecimal price = cart.getProductId() == null ? null : priceByVariantId.get(cart.getProductId());
                if (price == null) {
                    budgets.computeIfPresent(projectId, (ignored, budget) -> budget.pendingPricing());
                    continue;
                }
                budgets.computeIfPresent(projectId, (ignored, budget) -> budget.add(price.multiply(BigDecimal.valueOf(cart.getQty()))));
            }
        } catch (RuntimeException catalogUnavailable) {
            log.warn("项目预算批量查价失败，将展示为不可用: {}", catalogUnavailable.getMessage());
            projectIdsWithCart.forEach(projectId -> budgets.put(projectId, ProjectBudget.unavailable()));
        }
        return budgets;
    }

    @Transactional
    public OfficeProjectDto createProject(CreateOfficeProjectRequest request) {
        String workspaceId = accessService.workspaceForProjectCreation();
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
        if (membersProvided || workspaceId != null) {
            leaderUserId = request.getLeaderUserId();
            collaboratorIds = validateMemberSet(workspaceId, leaderUserId, request.getCollaboratorUserIds());
        }

        LocalDateTime now = LocalDateTime.now();
        OfficeDesignProject project = new OfficeDesignProject();
        project.setProjectId(IdGenerator.spatialId("OPRJ"));
        project.setName(projectName);
        project.setStatus("DESIGNING");
        project.setCustomerRequirements(request.getCustomerRequirements() == null ? "" : request.getCustomerRequirements());
        project.setRequirementsRevision(1);
        project.setWorkspaceId(workspaceId);
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
            members = insertMembers(project.getProjectId(), leaderUserId, collaboratorIds, now, Map.of());
        }
        return toProjectDto(project, floors, members, usersById(members));
    }

    @Transactional
    public OfficeFloorDto addFloor(String projectId, CreateOfficeFloorRequest request) {
        accessService.requirePermission(projectId, OfficeProjectPermission.EDIT);
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
        accessService.requirePermission(projectId, OfficeProjectPermission.EDIT);
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
    public String updateStatus(String projectId, String status) {
        accessService.requireStatusManager(projectId);
        requireProject(projectId);
        if (status == null || !Set.of("DESIGNING", "DELIVERING", "COMPLETED").contains(status)) {
            throw new BusinessException("项目阶段必须为设计中、交付中或已完成");
        }
        if (projectMapper.updateStatus(projectId, status, LocalDateTime.now()) != 1) {
            throw new BusinessException("项目不存在，请刷新项目列表");
        }
        auditService.record("PROJECT_STATUS_CHANGED", "PROJECT", projectId, "status=" + status);
        return status;
    }

    @Transactional
    public OfficeFloorDto updateFloor(String projectId, String floorId, CreateOfficeFloorRequest request) {
        accessService.requirePermission(projectId, OfficeProjectPermission.EDIT);
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
        accessService.requirePermission(projectId, OfficeProjectPermission.EDIT);
        requireProject(projectId);
        if (request == null || request.getCustomerRequirements() == null) {
            throw new BusinessException("客户需求不能为空");
        }
        projectMapper.updateRequirements(projectId, request.getCustomerRequirements(), LocalDateTime.now());
        OfficeDesignProject project = requireProject(projectId);
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
     * 成员必须同属项目工作区；替换前后集合写入审计日志。
     */
    @Transactional
    public OfficeProjectDto replaceMembers(String projectId, UpdateProjectMembersRequest request) {
        memberMapper.lockProjectMembers(projectId);
        accessService.requireLeader(projectId);
        OfficeDesignProject project = requireProject(projectId);
        if (request == null) {
            throw new BusinessException("成员参数不能为空");
        }
        List<String> collaboratorIds = validateMemberSet(
            project.getWorkspaceId(), request.getLeaderUserId(), request.getCollaboratorUserIds());
        List<OfficeProjectMember> previous = members(projectId);
        String leaderId = request.getLeaderUserId().trim();
        if (!accessService.ownsProject(projectId) && (request.getPermissions() != null || previous.stream().noneMatch(member ->
                OfficeProjectMember.ROLE_LEADER.equals(member.getRole()) && leaderId.equals(member.getUserId())))) {
            throw new AccessDeniedException("只有工作区主号可以授权或更换负责人");
        }
        var grants = new HashMap<String, Integer>();
        previous.forEach(member -> grants.put(member.getUserId(), member.getPermissionFlags() == null ? 0 : member.getPermissionFlags()));
        if (request.getPermissions() != null) {
            request.getPermissions().forEach((userId, permissions) -> {
                if (!leaderId.equals(userId) && !collaboratorIds.contains(userId)) {
                    throw new BusinessException("授权对象必须是本项目成员");
                }
                grants.put(userId, OfficeProjectPermission.encode(permissions));
            });
        }
        List<String> before = previous.stream().map(this::memberAuditText).toList();
        memberMapper.delete(new QueryWrapper<OfficeProjectMember>().eq("project_id", projectId));
        List<OfficeProjectMember> members = insertMembers(projectId, leaderId, collaboratorIds, LocalDateTime.now(), grants);
        List<String> after = members.stream().map(this::memberAuditText).toList();
        auditService.record("MEMBERS_REPLACED", "PROJECT", projectId,
            "before=" + before + "; after=" + after);
        return toProjectDto(project, floors(projectId), members, usersById(members));
    }

    private String memberAuditText(OfficeProjectMember member) {
        return member.getRole() + ":" + member.getUserId() + "=" + OfficeProjectPermission.assigned(member.getPermissionFlags());
    }

    @Transactional
    public OfficeProjectDto updateReferences(String projectId, UpdateProjectReferencesRequest request) {
        accessService.requirePermission(projectId, OfficeProjectPermission.EDIT);
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

    /** 搜索可作为项目成员的有效用户；认证开启时只返回当前账号同工作区的在用用户。 */
    public List<AssignableUserDto> listAssignableUsers(String keyword) {
        QueryWrapper<OfficeUser> query = new QueryWrapper<OfficeUser>().eq("status", "active");
        if (accessService.enforcementEnabled()) {
            OfficeUser operator = accessService.currentUserOrNull();
            String workspaceId = operator != null ? operator.getWorkspaceId() : null;
            if (!StringUtils.hasText(workspaceId)) {
                return List.of();
            }
            query.eq("workspace_id", workspaceId);
        }
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
     * 项目已归属工作区时，所有成员账号必须同属该工作区；历史项目（无工作区）暂不限制。
     */
    private List<String> validateMemberSet(String workspaceId, String leaderUserId, List<String> collaboratorUserIds) {
        if (!StringUtils.hasText(leaderUserId)) {
            throw new BusinessException("项目负责人不能为空");
        }
        requireActiveUser(leaderUserId.trim(), "项目负责人不存在或已停用");
        requireWorkspaceMember(workspaceId, leaderUserId.trim(), "项目负责人不属于本项目工作区");
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
            requireWorkspaceMember(workspaceId, userId, "协作者不属于本项目工作区");
        }
        return List.copyOf(unique);
    }

    private void requireWorkspaceMember(String workspaceId, String userId, String message) {
        if (!StringUtils.hasText(workspaceId)) {
            return;
        }
        OfficeUser user = userMapper.selectById(userId);
        if (user == null || !workspaceId.equals(user.getWorkspaceId())) {
            throw new BusinessException(message);
        }
    }

    private void requireActiveUser(String userId, String message) {
        OfficeUser user = userMapper.selectById(userId);
        if (user == null || !"active".equalsIgnoreCase(user.getStatus())) {
            throw new BusinessException(message);
        }
    }

    private List<OfficeProjectMember> insertMembers(String projectId, String leaderUserId,
                                                    List<String> collaboratorIds, LocalDateTime now, Map<String, Integer> grants) {
        List<OfficeProjectMember> members = new ArrayList<>();
        OfficeProjectMember leader = newMember(projectId, leaderUserId, OfficeProjectMember.ROLE_LEADER, now);
        leader.setPermissionFlags(grants.getOrDefault(leaderUserId, 0));
        try {
            memberMapper.insert(leader);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("项目负责人冲突，请刷新后重试");
        }
        members.add(leader);
        for (String userId : collaboratorIds) {
            OfficeProjectMember collaborator = newMember(projectId, userId, OfficeProjectMember.ROLE_COLLABORATOR, now);
            collaborator.setPermissionFlags(grants.getOrDefault(userId, 0));
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
        dto.setStatus(project.getStatus() == null ? "DESIGNING" : project.getStatus());
        dto.setCanManageStatus(accessService.canManageStatus(project, members));
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

    private record ProjectBudget(BigDecimal total, String status) {
        private static final String COMPLETE = "COMPLETE";
        private static final String PENDING_PRICING = "PENDING_PRICING";
        private static final String UNAVAILABLE = "UNAVAILABLE";

        static ProjectBudget complete() {
            return new ProjectBudget(BigDecimal.ZERO, COMPLETE);
        }

        static ProjectBudget unavailable() {
            return new ProjectBudget(null, UNAVAILABLE);
        }

        ProjectBudget add(BigDecimal amount) {
            return new ProjectBudget(total.add(amount), status);
        }

        ProjectBudget pendingPricing() {
            return new ProjectBudget(total, PENDING_PRICING);
        }
    }

    private ProjectMemberDto toMemberDto(OfficeProjectMember member, OfficeUser user) {
        ProjectMemberDto dto = new ProjectMemberDto();
        dto.setUserId(member.getUserId());
        dto.setUsername(user != null ? user.getUsername() : "");
        dto.setDisplayName(user != null ? user.getDisplayName() : "");
        dto.setRole(member.getRole());
        dto.setPermissions(OfficeProjectPermission.assigned(member.getPermissionFlags()));
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
