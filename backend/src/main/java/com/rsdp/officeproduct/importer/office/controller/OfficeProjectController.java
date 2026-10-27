package com.rsdp.officeproduct.importer.office.controller;

import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.office.dto.CreateOfficeFloorRequest;
import com.rsdp.officeproduct.importer.office.dto.CreateOfficeProjectRequest;
import com.rsdp.officeproduct.importer.office.dto.OfficeFloorDto;
import com.rsdp.officeproduct.importer.office.dto.OfficeProjectDto;
import com.rsdp.officeproduct.importer.office.dto.UpdateOfficeProjectRequest;
import com.rsdp.officeproduct.importer.office.dto.UpdateOfficeRequirementsRequest;
import com.rsdp.officeproduct.importer.office.service.OfficeProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/office/projects")
@RequiredArgsConstructor
public class OfficeProjectController {

    private final OfficeProjectService projectService;

    @GetMapping
    public Result<List<OfficeProjectDto>> listProjects() {
        return Result.ok(projectService.listProjects());
    }

    @PostMapping
    public Result<OfficeProjectDto> createProject(@RequestBody CreateOfficeProjectRequest request) {
        return Result.ok(projectService.createProject(request));
    }

    @PostMapping("/{projectId}/floors")
    public Result<OfficeFloorDto> addFloor(@PathVariable String projectId, @RequestBody CreateOfficeFloorRequest request) {
        return Result.ok(projectService.addFloor(projectId, request));
    }

    @PatchMapping("/{projectId}")
    public Result<OfficeProjectDto> updateProject(@PathVariable String projectId,
                                                   @RequestBody UpdateOfficeProjectRequest request) {
        return Result.ok(projectService.updateProject(projectId, request));
    }

    @PatchMapping("/{projectId}/floors/{floorId}")
    public Result<OfficeFloorDto> updateFloor(@PathVariable String projectId, @PathVariable String floorId,
                                               @RequestBody CreateOfficeFloorRequest request) {
        return Result.ok(projectService.updateFloor(projectId, floorId, request));
    }

    @PatchMapping("/{projectId}/requirements")
    public Result<OfficeProjectDto> updateRequirements(@PathVariable String projectId,
                                                        @RequestBody UpdateOfficeRequirementsRequest request) {
        return Result.ok(projectService.updateRequirements(projectId, request));
    }
}
