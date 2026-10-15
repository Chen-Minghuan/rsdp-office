package com.rsdp.officeproduct.importer.controller;

import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.dto.request.SpaceTemplateCreateRequest;
import com.rsdp.officeproduct.importer.dto.response.SpaceTemplateResponse;
import com.rsdp.officeproduct.importer.service.SpaceTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/office/projects/{projectId}/space-templates")
public class SpaceTemplateController {
    private final SpaceTemplateService service;
    @PostMapping public Result<SpaceTemplateResponse> create(@PathVariable String projectId, @Valid @RequestBody SpaceTemplateCreateRequest request) {
        return Result.ok(service.create(projectId, request));
    }
    @GetMapping public Result<List<SpaceTemplateResponse>> list(@PathVariable String projectId) {
        return Result.ok(service.list(projectId));
    }
    @GetMapping("/{templateId}") public Result<SpaceTemplateResponse> get(@PathVariable String projectId, @PathVariable String templateId) {
        return Result.ok(service.get(projectId, templateId));
    }
}
