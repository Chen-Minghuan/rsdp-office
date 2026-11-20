package com.rsdp.officeproduct.importer.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.office.service.OfficeCatalogAccess;
import com.rsdp.officeproduct.importer.service.RsdpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/** Office-facing adapter for historical RoomVIP import results. */
@RestController
@RequestMapping("/api/v1/products/import-batches")
@RequiredArgsConstructor
public class OfficeProductBatchController {
    private static final String MAIN = "/api/v1/products/office/batches";
    private final OfficeCatalogAccess access;
    private final RsdpClient rsdp;

    @GetMapping
    public Result<JsonNode> history() {
        return Result.ok(rsdp.get(historyPath(MAIN)));
    }

    @GetMapping("/{batchId}/status")
    public Result<JsonNode> historyStatus(@PathVariable String batchId) {
        if (!batchId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException("导入批次无效");
        }
        return Result.ok(rsdp.get(historyPath(MAIN + "/" + batchId + "/status")));
    }

    private String historyPath(String path) {
        var actor = access.requireHistoryReader();
        return UriComponentsBuilder.fromPath(path)
            .queryParam("workspaceId", "{workspaceId}").queryParam("factoryCode", "{factoryCode}")
            .queryParam("actorId", "{actorId}").queryParam("actorName", "{actorName}")
            .encode().buildAndExpand(actor.workspaceId(), actor.factoryCode(), actor.actorId(), actor.actorName())
            .toUriString();
    }
}
