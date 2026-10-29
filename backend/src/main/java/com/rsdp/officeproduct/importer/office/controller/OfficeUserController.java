package com.rsdp.officeproduct.importer.office.controller;

import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.office.dto.AssignableUserDto;
import com.rsdp.officeproduct.importer.office.service.OfficeProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/office/users")
@RequiredArgsConstructor
public class OfficeUserController {

    private final OfficeProjectService projectService;

    @GetMapping("/assignable")
    public Result<List<AssignableUserDto>> listAssignableUsers(@RequestParam(required = false) String keyword) {
        return Result.ok(projectService.listAssignableUsers(keyword));
    }
}
