package com.rsdp.officeproduct.importer.office.product;

import com.rsdp.officeproduct.importer.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.rsdp.officeproduct.importer.office.product.LocalProductModels.*;

@RestController
@RequestMapping("/api/v1/office/local-products")
@RequiredArgsConstructor
public class LocalProductController {
    private final LocalProductService products;
    private final LocalProductWorkbook workbook;
    private final LocalProductImportAssistant assistant;
    private final WorkbookPreviewCache previewCache;
    private final LocalProductImportHistory importHistory;

    @GetMapping
    public Result<Page> list(@RequestParam(defaultValue = "1") int page,
                             @RequestParam(defaultValue = "") String search) {
        return Result.ok(products.list(page, search));
    }

    @GetMapping("/{productId}")
    public Result<Detail> detail(@PathVariable String productId) {
        return Result.ok(products.detail(productId));
    }

    @GetMapping("/capacity")
    public Result<Capacity> capacity() {
        return Result.ok(products.capacity());
    }

    @PostMapping
    public Result<List<Saved>> save(@RequestBody SaveRequest request) {
        return Result.ok(products.save(request));
    }

    @GetMapping("/imports")
    public Result<LocalProductImportHistory.Page> imports(@RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "") String search) {
        return Result.ok(importHistory.list(page, search));
    }

    @GetMapping("/imports/{importId}")
    public Result<LocalProductImportHistory.Detail> importDetail(@PathVariable UUID importId) {
        return Result.ok(importHistory.detail(importId));
    }

    @PostMapping("/excel-preview")
    public Result<LocalProductWorkbook.Preview> preview(@RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "false") boolean imageReferences,
            @RequestPart(value = "mapping", required = false) SupplierWorkbookMapping.Selection mapping) {
        return Result.ok(workbook.preview(file, mapping, imageReferences));
    }

    @GetMapping("/excel-preview/{previewId}/images/{key}")
    public ResponseEntity<byte[]> previewImage(@PathVariable UUID previewId, @PathVariable String key) {
        var image = previewCache.read(previewId, key, products.requireWriter());
        return imageResponse(image);
    }

    @PostMapping("/excel-field-suggestions")
    public Result<Map<SupplierWorkbookMapping.Field, Integer>> suggestFields(@RequestBody LocalProductImportAssistant.HeaderRequest request) {
        return Result.ok(assistant.suggestFields(request));
    }

    @PostMapping("/excel-category-suggestions")
    public Result<Map<Integer, String>> suggestCategories(@RequestBody LocalProductImportAssistant.CategoryRequest request) {
        return Result.ok(assistant.suggestCategories(request));
    }

    @PostMapping("/excel-material-suggestions")
    public Result<Map<Integer, String>> suggestMaterials(@RequestBody LocalProductImportAssistant.MaterialRequest request) {
        return Result.ok(assistant.suggestMaterials(request));
    }

    @GetMapping("/excel-template")
    public ResponseEntity<byte[]> template() throws IOException {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=office-products.xlsx")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .cacheControl(CacheControl.noStore()).body(workbook.template());
    }

    @GetMapping("/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable String imageId) {
        return imageResponse(products.image(imageId));
    }

    private ResponseEntity<byte[]> imageResponse(ImageInput image) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mediaType()))
            .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noStore()).body(image.content());
    }
}
