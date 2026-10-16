package com.rsdp.officeproduct.importer.office.controller;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.office.dto.RenderPreviewRequest;
import com.rsdp.officeproduct.importer.office.service.RenderPreviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/office/projects/{projectId}/render-previews")
@RequiredArgsConstructor
public class RenderPreviewController {
    private final RenderPreviewService service;
    @PostMapping
    public Result<ObjectNode> create(@PathVariable String projectId,@RequestBody RenderPreviewRequest request) { return Result.ok(service.create(projectId,request)); }
    @GetMapping
    public Result<List<ObjectNode>> list(@PathVariable String projectId,@RequestParam String zoneId) { return Result.ok(service.list(projectId,zoneId)); }
    @GetMapping("/{previewId}")
    public Result<ObjectNode> get(@PathVariable String projectId,@PathVariable String previewId) { return Result.ok(service.get(projectId,previewId)); }
}
