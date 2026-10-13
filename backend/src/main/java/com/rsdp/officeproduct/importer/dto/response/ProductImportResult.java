package com.rsdp.officeproduct.importer.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 产品批量导入结果。
 */
@Data
public class ProductImportResult {

    private int totalRows;
    private int successCount;
    private int failedCount;
    private int skippedCount;
    private int productCount;
    private int variantCount;
    private List<ProductImportFailure> failures = new ArrayList<>();
    private List<ProductImportFailure> warnings = new ArrayList<>();
    private DataCleansingReport cleansingReport = new DataCleansingReport();
}
