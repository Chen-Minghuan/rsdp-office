package com.rsdp.officeproduct.importer.office.controller;

import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.office.dto.AdminUserDto;
import com.rsdp.officeproduct.importer.office.dto.CreateOfficeUserRequest;
import com.rsdp.officeproduct.importer.office.dto.ResetOfficeUserPasswordRequest;
import com.rsdp.officeproduct.importer.office.dto.UpdateOfficeUserStatusRequest;
import com.rsdp.officeproduct.importer.office.service.OfficeUserAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 系统管理员用户管理；管理员身份在服务端逐接口校验，不只靠前端隐藏入口。 */
@RestController
@RequestMapping("/api/v1/office/admin/users")
@RequiredArgsConstructor
public class OfficeUserAdminController {

    private final OfficeUserAdminService userAdminService;

    @GetMapping
    public Result<List<AdminUserDto>> listUsers() {
        return Result.ok(userAdminService.listUsers());
    }

    @PostMapping
    public Result<AdminUserDto> createUser(@RequestBody CreateOfficeUserRequest request) {
        return Result.ok(userAdminService.createUser(request));
    }

    @PatchMapping("/{userId}/status")
    public Result<AdminUserDto> updateStatus(@PathVariable String userId, @RequestBody UpdateOfficeUserStatusRequest request) {
        return Result.ok(userAdminService.updateStatus(userId, request == null ? null : request.getStatus()));
    }

    @PostMapping("/{userId}/password")
    public Result<AdminUserDto> resetPassword(@PathVariable String userId, @RequestBody ResetOfficeUserPasswordRequest request) {
        return Result.ok(userAdminService.resetPassword(userId, request == null ? null : request.getPassword()));
    }

    @DeleteMapping("/{userId}")
    public Result<Void> deleteUser(@PathVariable String userId) {
        userAdminService.deleteUser(userId);
        return Result.ok(null);
    }
}
