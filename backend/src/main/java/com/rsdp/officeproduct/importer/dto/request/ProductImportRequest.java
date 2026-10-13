package com.rsdp.officeproduct.importer.dto.request;

import com.rsdp.officeproduct.importer.dto.response.ProductImportPreviewRow;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** 用户确认的行数据；确认接口始终重新校验，不信任预览中的校验结果。 */
@Data
public class ProductImportRequest {
    private boolean updateIfExists;
    private List<ProductImportPreviewRow> rows = new ArrayList<>();
}
