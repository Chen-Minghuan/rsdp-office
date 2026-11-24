package com.rsdp.officeproduct.importer.office.dto;

import java.math.BigDecimal;

public record OfficeProjectBudgetDto(BigDecimal budgetTotal, String budgetStatus) {}
