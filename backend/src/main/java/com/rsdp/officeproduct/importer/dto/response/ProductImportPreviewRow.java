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
    private int sourceSheetIndex;
    private int sourceRowIndex;
    private String sourceSheetName;
    /** Original supplier size cell when one physical row expands into multiple exact variants. */
    private String sourceSizeText;
    private int sourceVariantCount;
    private String sourceVariantNote;
    private int embeddedImageCount;
    /** Zero-based index in the source workbook's ordered images; null keeps an existing URL as primary. */
    private Integer primaryEmbeddedImageIndex;
    /** Null means all source images are selected; an empty list means none. */
    private List<Integer> selectedEmbeddedImageIndexes;
    /** Only small, read-only previews from the initial workbook upload; never used for import. */
    private List<String> imagePreviews = new ArrayList<>();
    private List<String> imageWarnings = new ArrayList<>();
    private List<String> reviewWarnings = new ArrayList<>();
    private Map<String, String> fields = new LinkedHashMap<>();
    private boolean skipped;
    private String groupKey;
    private String action;
    private List<String> errors = new ArrayList<>();
}
