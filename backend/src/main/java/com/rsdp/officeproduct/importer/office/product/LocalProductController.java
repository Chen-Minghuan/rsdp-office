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

import static com.rsdp.officeproduct.importer.office.product.LocalProductModels.*;

@RestController
@RequestMapping("/api/v1/office/local-products")
@RequiredArgsConstructor
public class LocalProductController {
    private final LocalProductService products;
    private final LocalProductWorkbook workbook;

    @GetMapping
    public Result<Page> list(@RequestParam(defaultValue = "1") int page) {
        return Result.ok(products.list(page));
    }

    @GetMapping("/{productId}")
    public Result<Detail> detail(@PathVariable String productId) {
        return Result.ok(products.detail(productId));
    }

    @PostMapping
    public Result<List<Saved>> save(@RequestBody SaveRequest request) {
        return Result.ok(products.save(request));
    }

    @PostMapping("/excel-preview")
    public Result<LocalProductWorkbook.Preview> preview(@RequestPart("file") MultipartFile file) {
        return Result.ok(workbook.preview(file));
    }

    @GetMapping("/excel-template")
    public ResponseEntity<byte[]> template() throws IOException {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=office-products.xlsx")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .cacheControl(CacheControl.noStore()).body(workbook.template());
    }

    @GetMapping("/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable String imageId) {
        ImageInput image = products.image(imageId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mediaType()))
            .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noStore()).body(image.content());
    }
}
