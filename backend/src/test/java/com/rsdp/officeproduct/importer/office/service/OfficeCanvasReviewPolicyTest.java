package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OfficeCanvasReviewPolicyTest {
    private final ObjectMapper json = new ObjectMapper();

    private Map<String, Object> room(String id, boolean confirmed, String unit, List<List<Integer>> polygon) {
        return Map.of("id", id, "kind", "zone", "confirmed", confirmed, "reviewUnit", unit, "polygon", polygon);
    }
    private List<List<Integer>> rect(int x, int y, int width, int depth) {
        return List.of(List.of(x, y), List.of(x + width, y), List.of(x + width, y + depth), List.of(x, y + depth));
    }
    private JsonNode objects(Map<String, Object>... rooms) { return json.valueToTree(List.of(rooms)); }

    @Test void allowsUnreviewedRoomsSharingAnEdge() {
        assertDoesNotThrow(() -> OfficeCanvasReviewPolicy.requireLayoutReady(objects(room("A", false, "mm", rect(0, 0, 6000, 4000)), room("B", false, "mm", rect(6000, 0, 3000, 4000))), "A"));
    }
    @Test void blocksUnknownUnitsAndUnknownSelectedRoom() {
        assertThrows(BusinessException.class, () -> OfficeCanvasReviewPolicy.requireLayoutReady(objects(room("A", true, "source", rect(0, 0, 6000, 4000))), "A"));
        assertThrows(BusinessException.class, () -> OfficeCanvasReviewPolicy.requireLayoutReady(objects(room("A", true, "mm", rect(0, 0, 6000, 4000))), "MISSING"));
    }
    @Test void rejectsSelfIntersectionEvenIfClientMarksConfirmed() {
        assertThrows(BusinessException.class, () -> OfficeCanvasReviewPolicy.requireLayoutReady(objects(room("A", true, "mm", List.of(List.of(0, 0), List.of(6000, 4000), List.of(0, 4000), List.of(6000, 0)))), "A"));
    }
    @Test void overlapAndMissingReviewFlagsAreAdvisoryOnly() {
        assertDoesNotThrow(() -> OfficeCanvasReviewPolicy.requireLayoutReady(objects(
            room("A", false, "mm", rect(0, 0, 6000, 4000)), room("B", false, "mm", rect(5000, 0, 3000, 4000))), "A"));
        assertDoesNotThrow(() -> OfficeCanvasReviewPolicy.requireLayoutReady(objects(Map.of(
            "id", "A", "kind", "zone", "reviewUnit", "mm", "polygon", rect(0, 0, 6000, 4000))), "A"));
    }
    @Test void rejectsDuplicateIdsAndMissingOrDegenerateGeometry() {
        assertThrows(BusinessException.class, () -> OfficeCanvasReviewPolicy.requireLayoutReady(objects(
            room("A", false, "mm", rect(0, 0, 6000, 4000)), room("A", false, "mm", rect(7000, 0, 3000, 4000))), "A"));
        assertThrows(BusinessException.class, () -> OfficeCanvasReviewPolicy.requireLayoutReady(objects(room("A", false, "mm", List.of())), "A"));
        assertThrows(BusinessException.class, () -> OfficeCanvasReviewPolicy.requireLayoutReady(objects(room("A", false, "mm", rect(0, 0, 0, 4000))), "A"));
    }
    @Test void retainsConcaveFootprintAndAllowsRoomInItsNotch() {
        var concave = List.of(List.of(0, 0), List.of(6000, 0), List.of(6000, 2000), List.of(2000, 2000), List.of(2000, 6000), List.of(0, 6000));
        assertDoesNotThrow(() -> OfficeCanvasReviewPolicy.requireLayoutReady(objects(room("A", true, "mm", concave), room("B", true, "mm", rect(2000, 2000, 4000, 4000))), "A"));
    }
}
