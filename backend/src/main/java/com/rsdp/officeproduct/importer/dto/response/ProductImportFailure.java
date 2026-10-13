package com.rsdp.officeproduct.importer.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductImportFailure {

    private Integer rowIndex;
    private String externalCode;
    private String rspuId;
    private String reason;
}
