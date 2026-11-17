package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
public class ModuleRecommendationDto {
    private String moduleId;
    private String zoneId;
    private Integer canvasRevision;
    private BigDecimal widthMm;
    private BigDecimal depthMm;
    private String targetCategory;
    private int sameModuleCount;
    private int missingDimensions;
    private int eligibleVariantCount;
    private List<Candidate> candidates;

    @Data
    public static class Candidate {
        private OfficeProductDto product;
        private String tier;
        private BigDecimal fitRatio;
        private BigDecimal relevanceScore;
        private String matchReason;
        private int rotationDeg;
        private BigDecimal remainingWidthMm;
        private BigDecimal remainingDepthMm;
    }

    @Data
    public static class Selection {
        private CanvasDto canvas;
        private List<CartItemDto> cartItems;
        private Candidate match;
    }
}
