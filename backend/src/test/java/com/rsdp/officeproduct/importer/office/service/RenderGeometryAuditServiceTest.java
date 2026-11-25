package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.config.RenderProperties;
import com.rsdp.officeproduct.importer.service.QwenVisionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RenderGeometryAuditServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    private final RenderGeometryAuditService service=new RenderGeometryAuditService(mock(QwenVisionClient.class),new RenderProperties());
    private RenderSpatialControl.Prepared spatial;
    @BeforeEach void setup() { var scene=RenderSpatialControlTest.meetingScene(json);spatial=RenderSpatialControl.prepare(scene,RenderFurnitureBindings.from(scene)); }

    private ObjectNode perfect() {
        ObjectNode raw=json.createObjectNode();raw.putObject("composition").put("singleScene",true).put("containsReferenceBoard",false).put("containsPlan",false).put("containsLabels",false).put("containsCollage",false).put("cameraMatchesGuide",true).put("status","PASS");
        ArrayNode room=raw.putObject("room").put("status","PASS").putArray("guidePolygon1000");spatial.room().guidePolygon().forEach(point->room.addArray().add(point.x()).add(point.y()));
        ArrayNode observations=raw.putArray("observations");List<RenderSpatialControl.FurnitureControl> reversed=new ArrayList<>(spatial.furniture());Collections.reverse(reversed);
        reversed.forEach(item->observation(observations,item));return raw;
    }
    private void observation(ArrayNode target,RenderSpatialControl.FurnitureControl item) {
        ObjectNode observed=target.addObject().put("detectionId",item.referenceId()).put("category",item.referenceId().equals("F2")?"TABLE":"CHAIR").put("confidence",.95).put("visibility","VISIBLE");
        observed.putArray("groundCenter1000").add(item.guide().groundCenter().x()).add(item.guide().groundCenter().y());
        ArrayNode polygon=observed.putArray("groundPolygon1000");item.guide().groundPolygon().forEach(point->polygon.addArray().add(point.x()).add(point.y()));
    }
    @Test void auditObservesResultIndependentlyWithoutExpectedCoordinatesOrCount() throws Exception {
        QwenVisionClient vision=mock(QwenVisionClient.class);RenderGeometryAuditService auditing=new RenderGeometryAuditService(vision,new RenderProperties());
        when(vision.auditRender(anyString(),anyList())).thenReturn(perfect());
        auditing.audit(new byte[]{1},new byte[]{2},new byte[]{3},spatial);
        var prompt=org.mockito.ArgumentCaptor.forClass(String.class);var images=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(vision).auditRender(prompt.capture(),images.capture());
        assertFalse(prompt.getValue().contains("worldCenterMm"));assertFalse(prompt.getValue().contains("SPATIAL_CONTROL_V1"));assertFalse(prompt.getValue().contains("\"furnitureCount\""));
        assertTrue(prompt.getValue().contains("穷举其中每一个家具实例"));assertTrue(prompt.getValue().contains("重复家具"));assertTrue(prompt.getValue().contains("没有期望数量"));
        assertEquals(3,images.getValue().size());assertArrayEquals(new byte[]{1},(byte[])images.getValue().get(0));assertArrayEquals(new byte[]{2},(byte[])images.getValue().get(1));assertArrayEquals(new byte[]{3},(byte[])images.getValue().get(2));
    }
    @Test void compatibleCategoriesNeverOverrideExactGeometry() {
        ObjectNode raw=perfect();for(JsonNode observed:raw.withArray("observations")) {
            String id=observed.path("detectionId").asText();if(id.equals("F2"))((ObjectNode)observed).put("category","DESK");
            if(id.equals("F8")||id.equals("F9"))((ObjectNode)observed).put("category","STORAGE");
        }
        ObjectNode result=service.normalize(raw,spatial);assertEquals("PASS",result.path("status").asText(),result.toPrettyString());
        for(JsonNode item:result.path("items")) { assertEquals(item.path("referenceId").asText(),item.path("detectionId").asText());assertEquals("PASS",item.path("status").asText()); }
    }
    @Test void extraUnlabelledFurnitureRaisesObservedCountAndFails() {
        ObjectNode raw=perfect();ObjectNode extra=raw.withArray("observations").addObject().put("detectionId","EXTRA-1").put("category","CHAIR").put("confidence",.98).put("visibility","VISIBLE");
        extra.putArray("groundCenter1000").add(20).add(20);extra.putArray("groundPolygon1000").addArray().add(10).add(10);extra.withArray("groundPolygon1000").addArray().add(30).add(10);extra.withArray("groundPolygon1000").addArray().add(30).add(30);extra.withArray("groundPolygon1000").addArray().add(10).add(30);
        ObjectNode result=service.normalize(raw,spatial);assertEquals(10,result.path("furnitureCount").path("observed").asInt());assertEquals("FAIL",result.path("furnitureCount").path("status").asText());assertEquals("FAIL",result.path("status").asText());
    }    @Test void exactDetectionsPassWithOneToOneMatchingDespiteResponseOrder() {
        ObjectNode result=service.normalize(perfect(),spatial);assertEquals("PASS",result.path("status").asText(),result.toPrettyString());
        assertEquals(9,result.path("items").size());assertEquals(9,result.path("furnitureCount").path("observed").asInt());
        for(JsonNode item:result.path("items")) { assertEquals("PASS",item.path("status").asText());assertEquals(item.path("referenceId").asText(),item.path("detectionId").asText());assertEquals(1,item.path("groundPolygonIou").asDouble(),.0001); }
    }
    @Test void missingInvalidAndDuplicateDetectionsCannotPass() {
        ObjectNode missing=perfect();missing.withArray("observations").remove(0);assertEquals("FAIL",service.normalize(missing,spatial).path("status").asText());
        ObjectNode invalid=perfect();((ObjectNode)invalid.withArray("observations").get(0)).putArray("groundPolygon1000").addArray().add(0).add(0);
        assertEquals("FAIL",service.normalize(invalid,spatial).path("status").asText());
        ObjectNode duplicate=perfect();ObjectNode copy=(ObjectNode)duplicate.withArray("observations").get(0).deepCopy();copy.put("detectionId","DUPLICATE");duplicate.withArray("observations").add(copy);
        assertEquals("FAIL",service.normalize(duplicate,spatial).path("status").asText());
    }
    @Test void compositionAndCameraFailuresOverrideCountPass() {
        ObjectNode raw=perfect();((ObjectNode)raw.get("composition")).put("containsReferenceBoard",true).put("cameraMatchesGuide",false);
        ObjectNode result=service.normalize(raw,spatial);assertEquals("FAIL",result.path("composition").path("status").asText());assertEquals("FAIL",result.path("status").asText());
        assertEquals("PASS",result.path("furnitureCount").path("status").asText());assertEquals("UNCERTAIN",result.path("items").get(0).path("status").asText());
    }
    @Test void lowConfidenceOcclusionAndLargeCenterOffsetAreNotSilentPasses() {
        ObjectNode low=perfect();((ObjectNode)low.withArray("observations").get(0)).put("confidence",.2);assertEquals("UNCERTAIN",service.normalize(low,spatial).path("status").asText());
        ObjectNode occluded=perfect();((ObjectNode)occluded.withArray("observations").get(0)).put("visibility","OCCLUDED");assertEquals("UNCERTAIN",service.normalize(occluded,spatial).path("status").asText());
        ObjectNode moved=perfect();ArrayNode center=(ArrayNode)moved.withArray("observations").get(0).get("groundCenter1000");center.removeAll();center.add(0).add(0);
        assertEquals("FAIL",service.normalize(moved,spatial).path("status").asText());
    }
}
