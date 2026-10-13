package com.rsdp.officeproduct.importer.dto.response;

import lombok.Data;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 保留 Excel 物理行号和原始文本，错误数字也可在预览中修正。 */
@Data
public class ProductImportPreviewRow {
    private int rowIndex;
    private Map<String, String> fields = new LinkedHashMap<>();
    private boolean skipped;
    private String groupKey;
    private String action;
    private List<String> errors = new ArrayList<>();
}
