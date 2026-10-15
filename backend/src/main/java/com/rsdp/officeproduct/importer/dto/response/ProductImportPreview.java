package com.rsdp.officeproduct.importer.dto.response;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class ProductImportPreview {
    public record Column(String key, String label, String type) { }
    private List<Column> columns = new ArrayList<>();
    private List<ProductImportPreviewRow> rows = new ArrayList<>();
    private int productCount;
    private int invalidRows;
    private int skippedRows;
    private int embeddedImageCount;
    private int rowsWithImages;
    private DataCleansingReport cleansingReport = new DataCleansingReport();
}
