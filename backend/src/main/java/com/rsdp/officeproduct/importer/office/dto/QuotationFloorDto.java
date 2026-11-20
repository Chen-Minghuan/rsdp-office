package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 报价按楼层分组；楼层取自 office_design_floor，不再从图纸名推断。 */
@Data
public class QuotationFloorDto {

    private String floorId;
    private String floorName;
    private List<QuotationSpaceDto> spaces = new ArrayList<>();
    private BigDecimal factoryTotal = BigDecimal.ZERO;
    private BigDecimal dealTotal = BigDecimal.ZERO;
}
