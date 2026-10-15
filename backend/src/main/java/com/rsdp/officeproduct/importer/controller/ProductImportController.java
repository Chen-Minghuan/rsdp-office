package com.rsdp.officeproduct.importer.controller;

import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.dto.request.ManualProductEntryRequest;
import com.rsdp.officeproduct.importer.dto.request.ProductImportRequest;
import com.rsdp.officeproduct.importer.dto.response.ProductDetailDto;
import com.rsdp.officeproduct.importer.dto.response.ProductImportPreview;
import com.rsdp.officeproduct.importer.dto.response.ProductImportResult;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.entity.RspuMaster;
import com.rsdp.officeproduct.importer.service.ProductAuditService;
import com.rsdp.officeproduct.importer.service.ProductImportService;
import com.rsdp.officeproduct.importer.service.ProductQueryService;
import com.rsdp.officeproduct.importer.util.ProductImportTemplateBuilder;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Validated
public class ProductImportController {

    private final ProductImportService productImportService;
    private final ProductQueryService productQueryService;
    private final ProductAuditService productAuditService;

    @GetMapping
    public Result<List<ProductListItemDto>> listProducts() {
        return Result.ok(productQueryService.listProducts());
    }

    @GetMapping("/audit/pending")
    public Result<List<RspuMaster>> listPendingProducts() {
        return Result.ok(productAuditService.listPending());
    }

    @PostMapping("/{rspuId}/approve")
    public Result<RspuMaster> approveProduct(@PathVariable String rspuId) {
        return Result.ok(productAuditService.approve(rspuId));
    }

    @PostMapping("/{rspuId}/reject")
    public Result<RspuMaster> rejectProduct(@PathVariable String rspuId,
                                            @RequestBody(required = false) AuditRemarkRequest request) {
        return Result.ok(productAuditService.reject(rspuId, request != null ? request.getRemark() : null));
    }

    @GetMapping("/import-template")
    public void downloadTemplate(HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("办公产品导入模板", StandardCharsets.UTF_8)
            .replaceAll("\\+", "%20");
        response.setHeader("Content-disposition", "attachment;filename=" + fileName + ".xlsx");

        ProductImportTemplateBuilder.write(response.getOutputStream());
    }

    @PostMapping("/import")
    public Result<ProductImportResult> importProducts(
        @RequestPart("file") MultipartFile file,
        @RequestParam(value = "updateIfExists", required = false, defaultValue = "false") boolean updateIfExists) {
        return Result.ok(productImportService.importProducts(file, updateIfExists));
    }

    @PostMapping("/import-preview")
    public Result<ProductImportPreview> previewProducts(@RequestPart("file") MultipartFile file) {
        return Result.ok(productImportService.previewProducts(file));
    }

    @PostMapping("/import-preview/validate")
    public Result<ProductImportPreview> validatePreview(@RequestBody ProductImportRequest request) {
        return Result.ok(productImportService.validatePreview(request));
    }

    @PostMapping("/import-confirm")
    public Result<ProductImportResult> confirmImport(@RequestBody ProductImportRequest request) {
        return Result.ok(productImportService.importRows(request));
    }

    @PostMapping(value = "/import-confirm", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ProductImportResult> confirmImportWithFile(@RequestPart("file") MultipartFile file,
                                                              @RequestPart("request") ProductImportRequest request) {
        return Result.ok(productImportService.importRowsWithFile(file, request));
    }

    @PostMapping("/manual")
    public Result<Map<String, Object>> manualEntry(@RequestBody ManualProductEntryRequest request) {
        return Result.ok(productImportService.createManualEntry(request));
    }

    @GetMapping("/{rspuId}")
    public Result<ProductDetailDto> getProduct(@PathVariable String rspuId) {
        return Result.ok(productImportService.getProductDetail(rspuId));
    }

    @PutMapping("/{rspuId}")
    public Result<Map<String, Object>> updateProduct(@PathVariable String rspuId,
                                                     @RequestBody ManualProductEntryRequest request) {
        return Result.ok(productImportService.updateProduct(rspuId, request));
    }

    @DeleteMapping("/{rspuId}")
    public Result<Void> deleteProduct(@PathVariable String rspuId) {
        productImportService.deleteProduct(rspuId);
        return Result.ok(null);
    }

    @DeleteMapping("/batch")
    public Result<Integer> deleteProducts(@RequestBody List<String> rspuIds) {
        return Result.ok(productImportService.deleteProducts(rspuIds));
    }

    @PostMapping("/{rspuId}/restore")
    public Result<Void> restoreProduct(@PathVariable String rspuId) {
        productImportService.restoreProduct(rspuId);
        return Result.ok(null);
    }
}
