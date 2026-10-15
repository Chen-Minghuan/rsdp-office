package com.rsdp.officeproduct.importer.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class DimensionCalibrationConfirmRequest {
    @NotNull @Min(1)
    private Integer analysisRevision;
    @NotBlank
    private String reportId;
    @NotNull @Pattern(regexp = "mm|cm|m")
    private String assumedUnit;
}
