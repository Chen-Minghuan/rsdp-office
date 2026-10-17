package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoomLabelGroundingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void failedSecondaryUsesOnlyValidPrimaryTextBoxesAndPreservesBothInputs() throws Exception {
        var primary = mapper.readTree("""
            {"labels":[{"text":"办公室","bbox":[100,200,150,220]},
            {"text":"办公室","bbox":[100,200,150,220]},
            {"text":"坏框","bbox":[0,0,1000,1000]}, {"text":"无框","bbox":null}]}
            """);
        var secondary = mapper.createObjectNode().put("status", "UNAVAILABLE");
        var primaryBefore = primary.deepCopy(); var secondaryBefore = secondary.deepCopy();
        var merged = RoomLabelGrounding.merge(primary, secondary, mapper);
        assertEquals("DETECTED", merged.path("status").asText());
        assertEquals("UNAVAILABLE", merged.path("independentStatus").asText());
        assertEquals(1, merged.path("labels").size());
        assertEquals("PRIMARY_SEMANTIC_LABEL", merged.path("labels").get(0).path("source").asText());
        assertEquals(primaryBefore, primary); assertEquals(secondaryBefore, secondary);
    }

    @Test
    void repeatedNamesFillTheMissingLocationWithoutDuplicatingIndependentOccurrences() throws Exception {
        var primary = mapper.readTree("""
            {"labels":[{"text":"主管室","bbox":[100,200,130,220]},
            {"text":"主管室","bbox":[300,200,330,220]},
            {"text":"主管室","bbox":[500,200,530,220]}]}
            """);
        var secondary = (ObjectNode) mapper.readTree("""
            {"status":"DETECTED","labels":[{"text":"主管室","bbox":[102,201,132,221]},
            {"text":"主管室","bbox":[502,201,532,221]}]}
            """);
        var result = RoomLabelGrounding.merge(primary, secondary, mapper);
        assertEquals(3, result.path("labels").size());
        assertEquals(1, result.path("primaryFallbackCount").asInt());
        assertEquals(300, result.path("labels").get(2).path("bbox").get(0).asInt());
    }

    @Test
    void completeIndependentGroupWinsOverShiftedPrimaryBoxes() throws Exception {
        var primary = mapper.readTree("{\"labels\":[{\"text\":\"办公室\",\"bbox\":[100,200,130,220]}]}");
        var secondary = (ObjectNode) mapper.readTree("{\"status\":\"DETECTED\",\"labels\":[{\"text\":\"办公室\",\"bbox\":[500,200,530,220]}]}");
        var result = RoomLabelGrounding.merge(primary, secondary, mapper);
        assertEquals(1, result.path("labels").size());
        assertEquals(0, result.path("primaryFallbackCount").asInt());
        assertEquals(500, result.path("labels").get(0).path("bbox").get(0).asInt());
    }
    @Test
    void hybridCandidatesUseExplicitTextBoxesAndNeverTheirRoomBounds() throws Exception {
        var primary = mapper.readTree("""
            {"rooms":[{"label":"会议室","labelBbox":[100,200,150,220],"bbox":[0,0,600,700]},
            {"label":"办公区","bbox":[300,400,350,420],"polygon":[[0,0],[500,0],[500,500],[0,500]]}]}
            """);
        var before = primary.deepCopy();
        var result = RoomLabelGrounding.merge(primary, null, mapper);
        assertEquals(1, result.path("labels").size());
        assertEquals("会议室", result.path("labels").get(0).path("text").asText());
        assertEquals(primary.path("rooms").get(0).path("labelBbox"), result.path("labels").get(0).path("bbox"));
        assertEquals(before, primary);
    }

}
