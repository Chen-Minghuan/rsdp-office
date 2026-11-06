package com.rsdp.officeproduct.importer.office.controller;

import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.office.dto.CreateWorkspaceRequest;
import com.rsdp.officeproduct.importer.office.dto.WorkspaceDto;
import com.rsdp.officeproduct.importer.office.service.OfficeWorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 系统管理员的经销商工作区管理；管理员身份在服务端逐接口校验。 */
@RestController
@RequestMapping("/api/v1/office/admin/workspaces")
@RequiredArgsConstructor
public class OfficeWorkspaceAdminController {

    private final OfficeWorkspaceService workspaceService;

    @GetMapping
    public Result<List<WorkspaceDto>> listWorkspaces() {
        return Result.ok(workspaceService.adminListWorkspaces());
    }

    @PostMapping
    public Result<WorkspaceDto> createWorkspace(@RequestBody CreateWorkspaceRequest request) {
        return Result.ok(workspaceService.adminCreateWorkspace(request));
    }
}
