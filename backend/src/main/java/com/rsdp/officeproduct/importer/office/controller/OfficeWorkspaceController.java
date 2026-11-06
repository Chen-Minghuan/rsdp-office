package com.rsdp.officeproduct.importer.office.controller;

import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.office.dto.CreateWorkspaceUserRequest;
import com.rsdp.officeproduct.importer.office.dto.WorkspaceDto;
import com.rsdp.officeproduct.importer.office.dto.WorkspaceUserDto;
import com.rsdp.officeproduct.importer.office.service.OfficeWorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 经销商主号的工作区入口：只能查看/操作自己拥有的工作区，身份在服务端逐接口校验。 */
@RestController
@RequestMapping("/api/v1/office/workspace")
@RequiredArgsConstructor
public class OfficeWorkspaceController {

    private final OfficeWorkspaceService workspaceService;

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
}
