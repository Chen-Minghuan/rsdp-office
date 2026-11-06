package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.auth.PasswordHasher;
import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.mapper.OfficeUserMapper;
import com.rsdp.officeproduct.importer.office.dto.CreateWorkspaceRequest;
import com.rsdp.officeproduct.importer.office.dto.CreateWorkspaceUserRequest;
import com.rsdp.officeproduct.importer.office.dto.WorkspaceDto;
import com.rsdp.officeproduct.importer.office.dto.WorkspaceUserDto;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import com.rsdp.officeproduct.importer.office.entity.OfficeWorkspace;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignProjectMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceMapper;
import com.rsdp.officeproduct.importer.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 经销商工作区：系统管理员创建工作区与主号；经销商主号在自己工作区内开户。
 * 工作区是项目隔离单位（office_design_project.workspace_id），主号角色为 office_user.role='DEALER'。
 * 认证关闭（开发模式）时无法解析"我的工作区"，经销商接口返回明确业务错误；管理员接口沿用放行约定。
 */
@Service
@RequiredArgsConstructor
public class OfficeWorkspaceService {

    public static final String SOURCE_DEALER_STAFF = "DEALER_STAFF";
    public static final String SOURCE_FACTORY_SALES = "FACTORY_SALES";

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.\\-]{2,32}$");
    private static final int MIN_PASSWORD_LENGTH = 6;

    private final OfficeWorkspaceMapper workspaceMapper;
    private final OfficeUserMapper userMapper;
    private final OfficeDesignProjectMapper projectMapper;
    private final CurrentUserService currentUserService;
    private final OfficeAuditService auditService;

    // ---------- 系统管理员：工作区与经销商主号 ----------

    public List<WorkspaceDto> adminListWorkspaces() {
        currentUserService.requireAdmin();
        List<OfficeWorkspace> workspaces = workspaceMapper.selectList(
            new QueryWrapper<OfficeWorkspace>().orderByAsc("created_at").orderByAsc("workspace_id"));
        if (workspaces.isEmpty()) {
            return List.of();
        }
        Map<String, OfficeUser> ownersById = userMapper.selectBatchIds(
                workspaces.stream().map(OfficeWorkspace::getOwnerUserId).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(OfficeUser::getUserId, Function.identity()));
        return workspaces.stream().map(workspace -> toDto(workspace, ownersById.get(workspace.getOwnerUserId()))).toList();
    }

    /** 创建工作区与经销商主号，单事务：主号角色 DEALER、归属新建工作区。 */
    @Transactional
    public WorkspaceDto adminCreateWorkspace(CreateWorkspaceRequest request) {
        currentUserService.requireAdmin();
        if (request == null) {
            throw new BusinessException("工作区参数不能为空");
        }
        String name = requireName(request.getName(), "工作区名称");
        String username = requireUsername(request.getOwnerUsername());
        String displayName = requireDisplayName(request.getOwnerDisplayName());
        String password = requirePassword(request.getOwnerPassword());

        LocalDateTime now = LocalDateTime.now();
        OfficeUser owner = new OfficeUser();
        owner.setUserId("USR-" + UUID.randomUUID().toString().replace("-", ""));
        owner.setUsername(username);
        owner.setPasswordHash(PasswordHasher.hash(password));
        owner.setDisplayName(displayName);
        owner.setRole("DEALER");
        owner.setStatus("active");
        owner.setSource(SOURCE_DEALER_STAFF);
        owner.setCreatedAt(now);
        owner.setUpdatedAt(now);
        try {
            userMapper.insert(owner);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("用户名已存在");
        }

        OfficeWorkspace workspace = new OfficeWorkspace();
        workspace.setWorkspaceId(IdGenerator.spatialId("OWKS"));
        workspace.setName(name);
        workspace.setOwnerUserId(owner.getUserId());
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        workspaceMapper.insert(workspace);

        owner.setWorkspaceId(workspace.getWorkspaceId());
        userMapper.updateById(owner);

        auditService.record("WORKSPACE_CREATED", "WORKSPACE", workspace.getWorkspaceId(),
            "name=" + name + "; owner=" + username);
        return toDto(workspace, owner);
    }

    // ---------- 经销商主号：本工作区账号 ----------

    public WorkspaceDto myWorkspace() {
        return toDto(requireOwnedWorkspace(), null);
    }

    public List<WorkspaceUserDto> listWorkspaceUsers() {
        OfficeWorkspace workspace = requireOwnedWorkspace();
        return userMapper.selectList(new QueryWrapper<OfficeUser>()
                .eq("workspace_id", workspace.getWorkspaceId()).orderByAsc("created_at"))
            .stream().map(this::toUserDto).toList();
    }

    /** 在本工作区内开户：角色固定 USER，来源仅允许经销商员工/厂家业务员（一期不参与鉴权）。 */
    @Transactional
    public WorkspaceUserDto createWorkspaceUser(CreateWorkspaceUserRequest request) {
        OfficeWorkspace workspace = requireOwnedWorkspace();
        if (request == null) {
            throw new BusinessException("用户参数不能为空");
        }
        String username = requireUsername(request.getUsername());
        String displayName = requireDisplayName(request.getDisplayName());
        String password = requirePassword(request.getPassword());
        String source = StringUtils.hasText(request.getSource())
            ? request.getSource().trim().toUpperCase() : SOURCE_DEALER_STAFF;
        if (!SOURCE_DEALER_STAFF.equals(source) && !SOURCE_FACTORY_SALES.equals(source)) {
            throw new BusinessException("账号来源只能是经销商员工或厂家业务员");
        }
        OfficeUser duplicate = userMapper.selectOne(
            new QueryWrapper<OfficeUser>().eq("username", username).last("limit 1"));
        if (duplicate != null) {
            throw new BusinessException("用户名已存在");
        }
        LocalDateTime now = LocalDateTime.now();
        OfficeUser user = new OfficeUser();
        user.setUserId("USR-" + UUID.randomUUID().toString().replace("-", ""));
        user.setUsername(username);
        user.setPasswordHash(PasswordHasher.hash(password));
        user.setDisplayName(displayName);
        user.setRole("USER");
        user.setStatus("active");
        user.setWorkspaceId(workspace.getWorkspaceId());
        user.setSource(source);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        auditService.record("ACCOUNT_CREATED", "USER", user.getUserId(),
            "workspace=" + workspace.getWorkspaceId() + "; username=" + username + "; source=" + source);
        return toUserDto(user);
    }

    /** 当前登录的经销商主号拥有的工作区；开发模式或非主号明确报错。 */
    private OfficeWorkspace requireOwnedWorkspace() {
        if (!currentUserService.authEnabled()) {
            throw new BusinessException("工作区接口需要开启认证并登录经销商主号后使用");
        }
        OfficeUser user = currentUserService.currentUser();
        if (!"DEALER".equalsIgnoreCase(user.getRole())) {
            throw new AccessDeniedException("只有经销商主号可以管理工作区账号");
        }
        OfficeWorkspace workspace = workspaceMapper.selectOne(
            new QueryWrapper<OfficeWorkspace>().eq("owner_user_id", user.getUserId()).last("limit 1"));
        if (workspace == null) {
            throw new BusinessException("当前经销商主号未绑定工作区");
        }
        return workspace;
    }

    private String requireName(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(label + "不能为空");
        }
        String name = value.trim();
        if (name.length() > 200) {
            throw new BusinessException(label + "不能超过 200 个字符");
        }
        return name;
    }

    private String requireUsername(String username) {
        String value = username == null ? "" : username.trim();
        if (!USERNAME_PATTERN.matcher(value).matches()) {
            throw new BusinessException("用户名需为 2-32 位字母、数字或 _ . -");
        }
        return value;
    }

    private String requireDisplayName(String displayName) {
        String value = displayName == null ? "" : displayName.trim();
        if (!StringUtils.hasText(value)) {
            throw new BusinessException("显示名称不能为空");
        }
        return value;
    }

    private String requirePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessException("初始密码至少 6 位");
        }
        return password;
    }

    private WorkspaceDto toDto(OfficeWorkspace workspace, OfficeUser owner) {
        OfficeUser resolvedOwner = owner != null ? owner : userMapper.selectById(workspace.getOwnerUserId());
        WorkspaceDto dto = new WorkspaceDto();
        dto.setWorkspaceId(workspace.getWorkspaceId());
        dto.setName(workspace.getName());
        dto.setOwnerUserId(workspace.getOwnerUserId());
        dto.setOwnerUsername(resolvedOwner != null ? resolvedOwner.getUsername() : "");
        dto.setOwnerDisplayName(resolvedOwner != null ? resolvedOwner.getDisplayName() : "");
        dto.setOwnerStatus(resolvedOwner != null ? resolvedOwner.getStatus() : "");
        dto.setUserCount(userMapper.selectCount(new QueryWrapper<OfficeUser>()
            .eq("workspace_id", workspace.getWorkspaceId())));
        dto.setProjectCount(projectMapper.selectCount(
            new QueryWrapper<OfficeDesignProject>().eq("workspace_id", workspace.getWorkspaceId())));
        dto.setCreatedAt(workspace.getCreatedAt());
        return dto;
    }

    private WorkspaceUserDto toUserDto(OfficeUser user) {
        WorkspaceUserDto dto = new WorkspaceUserDto();
        dto.setUserId(user.getUserId());
        dto.setUsername(user.getUsername());
        dto.setDisplayName(user.getDisplayName());
        dto.setRole(user.getRole());
        dto.setSource(user.getSource());
        dto.setStatus(user.getStatus());
        dto.setCreatedAt(user.getCreatedAt());
        return dto;
    }
}
