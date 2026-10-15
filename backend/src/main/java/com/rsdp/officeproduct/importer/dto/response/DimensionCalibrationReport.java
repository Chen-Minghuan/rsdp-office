package com.rsdp.officeproduct.importer.dto.response;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/** A proposal remains separate from source units and design-space confirmation. */
@Data
public class DimensionCalibrationReport {
    private String reportId;
    private String policyVersion = "DIM-SCALE-1";
    private String analysisId;
    private Integer analysisRevision;
    private String imageSha256;
    private int imageWidth;
    private int imageHeight;
    private String assumedUnit;
    private String status;
    private Double mmPerPixel;
    private Double maxDeviationPercent;
    private int acceptedCount;
    private int validCount;
    private boolean unitAssumed;
    private List<String> warnings = new ArrayList<>();
    private List<Reference> references = new ArrayList<>();
    private String confirmationId;
    private String confirmedAt;

    @Data
    public static class Reference {
        private String referenceId;
        private String text;
        private String unit;
        private boolean unitAssumed;
        private String confidence;
        private List<Double> start;
        private List<Double> end;
        private Double lengthMm;
        private Double pixelLength;
        private Double mmPerPixel;
        private String axis;
        private String status;
        private String reason;
    }
}
