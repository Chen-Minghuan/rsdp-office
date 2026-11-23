package com.rsdp.officeproduct.importer.office.controller;

import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.office.dto.OfficeFavoriteFolderDto;
import com.rsdp.officeproduct.importer.office.service.OfficeFavoriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/office/favorites")
@RequiredArgsConstructor
public class OfficeFavoriteController {

    private final OfficeFavoriteService favoriteService;

    @GetMapping("/folders")
    public Result<List<OfficeFavoriteFolderDto>> folders(@RequestParam(required = false) String rspuId) {
        return Result.ok(favoriteService.listFolders(rspuId));
    }

    @PostMapping("/folders")
    public Result<OfficeFavoriteFolderDto> createFolder(@RequestBody CreateFolderRequest request) {
        return Result.ok(favoriteService.createFolder(request.name()));
    }

    @DeleteMapping("/folders/{folderId}")
    public Result<Void> deleteFolder(@PathVariable String folderId) {
        favoriteService.deleteFolder(folderId);
        return Result.ok(null);
    }

    @PutMapping("/products/{rspuId}")
    public Result<Void> saveProductFolders(@PathVariable String rspuId,
                                           @RequestBody SaveProductFoldersRequest request) {
        favoriteService.saveProductFolders(rspuId, request.folderIds());
        return Result.ok(null);
    }

    @GetMapping("/product-ids")
    public Result<List<String>> favoriteProductIds() {
        return Result.ok(favoriteService.listFavoriteProductIds());
    }

    @GetMapping("/items")
    public Result<List<ProductListItemDto>> favoriteItems(@RequestParam(required = false) String folderId) {
        return Result.ok(favoriteService.listFavoriteItems(folderId));
    }

    public record CreateFolderRequest(String name) {
    }

    public record SaveProductFoldersRequest(List<String> folderIds) {
    }
}
