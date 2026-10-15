package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DimensionScaleEstimatorTest {
    private final ObjectMapper json = new ObjectMapper();
    private ObjectNode ref(String text, int x1, int y1, int x2, int y2) {
        ObjectNode node = json.createObjectNode().put("text", text).put("confidence", "high");
        node.putArray("start").add(x1).add(y1); node.putArray("end").add(x2).add(y2);
        return node;
    }
    private ArrayNode refs(ObjectNode... nodes) {
        ArrayNode array = json.createArrayNode(); for (ObjectNode node : nodes) array.add(node); return array;
    }
    @Test void computesFromTextAndEndpointsAcrossNonSquareImageWithoutConfirming() {
        var result = DimensionScaleEstimator.estimate(refs(ref("2,000 mm", 0, 0, 1000, 0), ref("1米", 0, 0, 0, 1000)), 200, 100, "HASH", "REPORT", "mm");
        assertEquals("READY", result.getStatus());
        assertEquals(10, result.getMmPerPixel(), 1e-9);
        assertFalse(result.isUnitAssumed());
        assertNull(result.getConfirmationId());
        assertEquals(2, result.getAcceptedCount());
        assertEquals(100, result.getReferences().get(1).getPixelLength());
    }
    @Test void bareNumbersRequireVisibleUnitAssumptionAndCanBeReinterpreted() {
        var input = refs(ref("6000", 100, 100, 700, 100), ref("4000", 100, 100, 100, 900));
        var mm = DimensionScaleEstimator.estimate(input, 1000, 500, "H", "R", "mm");
        var cm = DimensionScaleEstimator.estimate(input, 1000, 500, "H", "R", "cm");
        assertTrue(mm.isUnitAssumed()); assertFalse(mm.getWarnings().isEmpty());
        assertEquals(10, mm.getMmPerPixel()); assertEquals(100, cm.getMmPerPixel());
    }
    @Test void drawingUnitNoteIsEvidenceButUnsupportedUnitClaimsStayAssumptions() {
        var a = ref("6000", 100, 100, 700, 100).put("unit", "mm").put("unitEvidence", "单位：毫米");
        var b = ref("4000", 100, 100, 100, 900).put("unit", "mm").put("unitEvidence", "单位：毫米");
        assertFalse(DimensionScaleEstimator.estimate(refs(a, b), 1000, 500, "H", "R", "cm").isUnitAssumed());
        a.put("unit", "m").put("unitEvidence", "单位：mm");
        b.put("unit", "m").put("unitEvidence", "单位：mm");
        assertTrue(DimensionScaleEstimator.estimate(refs(a, b), 1000, 500, "H", "R", "cm").isUnitAssumed());
    }
    @Test void rejectsBadTextOutOfBoundsShortLinesAndLowConfidence() {
        var result = DimensionScaleEstimator.estimate(refs(ref("1:100", 0, 0, 1000, 0), ref("20 m²", 0, 0, 0, 1000),
            ref("6000", -1, 0, 1000, 0), ref("6000", 0, 0, 1, 0), ref("6000", 0, 0, 1000, 0).put("confidence", "low")), 1000, 500, "H", "R", "mm");
        assertEquals("NO_DIMENSIONS", result.getStatus()); assertNull(result.getMmPerPixel());
        assertEquals(5, result.getReferences().stream().filter(ref -> "REJECTED".equals(ref.getStatus())).count());
    }
    @Test void repeatedSameSegmentDoesNotCreateIndependentEvidence() {
        var result = DimensionScaleEstimator.estimate(refs(ref("6000", 100, 100, 700, 100), ref("6000", 700, 100, 100, 100)), 1000, 500, "H", "R", "mm");
        assertEquals("INSUFFICIENT", result.getStatus()); assertEquals(1, result.getValidCount()); assertNull(result.getMmPerPixel());
    }
    @Test void conflictingReadingsOfSameLineAreBlockedEvenWithinTolerance() {
        var result = DimensionScaleEstimator.estimate(refs(ref("6000", 100, 100, 700, 100), ref("6100", 700, 100, 100, 100)), 1000, 500, "H", "R", "mm");
        assertEquals("CONFLICT", result.getStatus()); assertNull(result.getMmPerPixel());
    }
    @Test void incompatibleHorizontalAndVerticalScalesBlockApplication() {
        var result = DimensionScaleEstimator.estimate(refs(ref("6000", 100, 100, 700, 100), ref("5000", 100, 100, 100, 900)), 1000, 500, "H", "R", "mm");
        assertEquals("CONFLICT", result.getStatus()); assertNull(result.getMmPerPixel());
        assertTrue(result.getWarnings().getLast().contains("横向与纵向"));
    }
    @Test void majorityRejectsOutlierAndReportsDeviationWithoutInventingVerticalValidation() {
        var result = DimensionScaleEstimator.estimate(refs(ref("6000", 0, 100, 600, 100), ref("4000", 0, 200, 400, 200), ref("2000", 0, 300, 200, 300), ref("9000", 0, 400, 300, 400)), 1000, 500, "H", "R", "mm");
        assertEquals("READY", result.getStatus()); assertEquals(3, result.getAcceptedCount());
        assertEquals("OUTLIER", result.getReferences().getLast().getStatus());
        assertTrue(result.getWarnings().getLast().contains("横纵"));
    }
    @Test void noMajorityAndMixedUnitsAreRecheckedInsteadOfTrustingPreviousConsensus() {
        var input = refs(ref("6000", 100, 100, 700, 100), ref("400 cm", 100, 100, 100, 900));
        assertEquals("READY", DimensionScaleEstimator.estimate(input, 1000, 500, "H", "R", "mm").getStatus());
        assertEquals("CONFLICT", DimensionScaleEstimator.estimate(input, 1000, 500, "H", "R", "cm").getStatus());
        var tied = refs(ref("6000", 0, 100, 600, 100), ref("4000", 0, 200, 400, 200), ref("4000", 0, 300, 200, 300), ref("6000", 0, 400, 300, 400));
        assertEquals("CONFLICT", DimensionScaleEstimator.estimate(tied, 1000, 500, "H", "R", "mm").getStatus());
        assertThrows(BusinessException.class, () -> DimensionScaleEstimator.estimate(input, 1000, 500, "H", "R", "inch"));
    }
}
