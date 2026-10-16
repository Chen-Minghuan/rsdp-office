package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import com.rsdp.officeproduct.importer.office.dto.ModuleRecommendationDto.Candidate;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Strict millimetre footprint comparison. Tiers rank every fitting size; none impose a minimum. */
public final class OfficeProductMatching {
    private OfficeProductMatching() {}

    public static BigDecimal positive(JsonNode node) {
        if (node == null || node.isNull() || (!node.isNumber() && !node.isTextual())) return null;
        try {
            BigDecimal number = new BigDecimal(node.asText());
            return number.signum() > 0 ? number : null;
        } catch (NumberFormatException invalid) { return null; }
    }

    public static BigDecimal[] dimensions(JsonNode node) {
        if (node == null || !node.isObject()) return new BigDecimal[]{null, null, null};
        String unit = node.path("unit").asText("");
        if (!unit.isEmpty() && !unit.equalsIgnoreCase("mm") && !unit.equalsIgnoreCase("cm")) return new BigDecimal[]{null, null, null};
        // Explicit mm keys take precedence. Conflicting units are not guessed.
        boolean mm = node.has("widthMm") || node.has("depthMm");
        if (mm && unit.equalsIgnoreCase("cm")) return new BigDecimal[]{null, null, null};
        String suffix = mm ? "Mm" : "Cm";
        BigDecimal factor = mm ? BigDecimal.ONE : BigDecimal.TEN;
        BigDecimal[] result = new BigDecimal[3];
        String[] axes = {"width", "depth", "height"};
        for (int i = 0; i < 3; i++) {
            BigDecimal value = positive(node.get(axes[i] + suffix));
            result[i] = value == null ? null : value.multiply(factor);
        }
        return result;
    }

    public static Candidate match(OfficeProductDto product, BigDecimal width, BigDecimal depth, boolean allowRotation) {
        if (width == null || depth == null || width.signum() <= 0 || depth.signum() <= 0 || product.getWidthMm() == null || product.getDepthMm() == null) return null;
        Candidate normal = orientation(product, width, depth, false);
        Candidate rotated = allowRotation ? orientation(product, width, depth, true) : null;
        return normal == null ? rotated : rotated != null && rotated.getFitRatio().compareTo(normal.getFitRatio()) > 0 ? rotated : normal;
    }

    private static Candidate orientation(OfficeProductDto product, BigDecimal width, BigDecimal depth, boolean rotated) {
        BigDecimal w = rotated ? product.getDepthMm() : product.getWidthMm();
        BigDecimal d = rotated ? product.getWidthMm() : product.getDepthMm();
        if (w.signum() <= 0 || d.signum() <= 0 || w.compareTo(width) > 0 || d.compareTo(depth) > 0) return null;
        BigDecimal ratio = w.divide(width, 12, RoundingMode.DOWN).min(d.divide(depth, 12, RoundingMode.DOWN));
        Candidate result = new Candidate();
        result.setProduct(product);
        result.setFitRatio(ratio);
        result.setTier(ratio.compareTo(new BigDecimal("0.85")) >= 0 ? "CLOSE" : ratio.compareTo(new BigDecimal("0.60")) >= 0 ? "RELAXED" : "SMALL");
        result.setRotationDeg(rotated ? 90 : 0);
        result.setRemainingWidthMm(width.subtract(w));
        result.setRemainingDepthMm(depth.subtract(d));
        return result;
    }
}
