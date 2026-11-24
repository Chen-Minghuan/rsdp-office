package com.rsdp.officeproduct.importer.office.controller;

import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.office.dto.OfficeProjectFavoriteDto;
import com.rsdp.officeproduct.importer.office.service.OfficeProjectFavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/office/projects")
@RequiredArgsConstructor
public class OfficeProjectFavoriteController {

    private final OfficeProjectFavoriteService favoriteService;

    @GetMapping("/{projectId}/favorites/product-ids")
    public Result<List<String>> favoriteProductIds(@PathVariable String projectId) {
        return Result.ok(favoriteService.listProjectFavoriteProductIds(projectId));
    }

    @GetMapping("/{projectId}/favorites/items")
    public Result<List<ProductListItemDto>> favoriteItems(@PathVariable String projectId) {
        return Result.ok(favoriteService.listProjectFavoriteItems(projectId));
    }

    @GetMapping("/{projectId}/favorites/{rspuId}")
    public Result<OfficeProjectFavoriteDto> favorite(@PathVariable String projectId, @PathVariable String rspuId) {
        return Result.ok(favoriteService.getProjectFavorite(projectId, rspuId));
    }

    @PutMapping("/{projectId}/favorites/{rspuId}")
    public Result<OfficeProjectFavoriteDto> save(@PathVariable String projectId,
                                                  @PathVariable String rspuId,
                                                  @RequestBody SaveProjectFavoriteRequest request) {
        return Result.ok(favoriteService.saveProjectFavorite(projectId, rspuId, request.selected()));
    }

    public record SaveProjectFavoriteRequest(Boolean selected) {
    }
}
