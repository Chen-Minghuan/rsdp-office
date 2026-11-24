package com.rsdp.officeproduct.importer.office.controller;

import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.office.dto.CreateWorkspaceUserRequest;
import com.rsdp.officeproduct.importer.office.dto.WorkspaceDto;
import com.rsdp.officeproduct.importer.office.dto.WorkspaceUserDto;
import com.rsdp.officeproduct.importer.office.service.OfficeWorkspaceService;
import com.rsdp.officeproduct.importer.office.service.SharedCatalogService;
import com.rsdp.officeproduct.importer.office.service.SharedCatalogService.SharedCatalogStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 经销商主号的工作区入口：只能查看/操作自己拥有的工作区，身份在服务端逐接口校验。 */
@RestController
@RequestMapping("/api/v1/office/workspace")
@RequiredArgsConstructor
public class OfficeWorkspaceController {

    private final OfficeWorkspaceService workspaceService;
    private final SharedCatalogService sharedCatalogService;

    @GetMapping("/me")
    public Result<WorkspaceDto> myWorkspace() {
        return Result.ok(workspaceService.myWorkspace());
    }

    @GetMapping("/users")
    public Result<List<WorkspaceUserDto>> listUsers() {
        return Result.ok(workspaceService.listWorkspaceUsers());
    }

    @PostMapping("/users")
    public Result<WorkspaceUserDto> createUser(@RequestBody CreateWorkspaceUserRequest request) {
        return Result.ok(workspaceService.createWorkspaceUser(request));
    }

    @PutMapping("/users/{userId}/product-entry")
    public Result<Void> setProductEntry(@PathVariable String userId, @RequestBody Map<String, Boolean> request) {
        if (request.get("enabled") == null) {
            throw new BusinessException("缺少商品录入权限设置");
        }
        workspaceService.setProductEntry(userId, request.get("enabled"));
        return Result.ok(null);
    }

    /** 公共库开关状态（工作区级 + 是否主号），前端回显用。 */
    @GetMapping("/shared-catalog")
    public Result<SharedCatalogStatus> sharedCatalogStatus() {
        return Result.ok(sharedCatalogService.currentStatus());
    }

    /** 工作区级公共库开关（仅经销商主号；关闭后全工作区仅见本厂产品，成员无个人开关）。 */
    @PutMapping("/shared-catalog")
    public Result<SharedCatalogStatus> setWorkspaceSharedCatalog(@RequestBody Map<String, Boolean> request) {
        sharedCatalogService.setWorkspaceSharedCatalog(Boolean.TRUE.equals(request.get("enabled")));
        return Result.ok(sharedCatalogService.currentStatus());
    }
}
