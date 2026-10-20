package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.rsdp.officeproduct.importer.dto.response.*;
import com.rsdp.officeproduct.importer.service.FloorPlanService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RenderLayoutContextTest {
    private final ObjectMapper json=new ObjectMapper();
    private final FloorPlanService floorPlans=mock(FloorPlanService.class);
    private final RenderLayoutContext context=new RenderLayoutContext(floorPlans);
    /** Frozen dimensions/positions from the user's 24-desk example, without product or personal data. */
    private ObjectNode office() throws Exception {
        ObjectNode scene=(ObjectNode)json.readTree("""
            {"zone":{"id":"ROOM","reviewUnit":"mm","polygon":[[41251.283538627154,5897.088505760847],[47303.024264394706,5897.088505760847],[47303.024264394706,23421.213916266577],[43142.45251542951,23421.213916266577],[43142.45251542951,26294.021360611787],[41251.283538627154,26294.021360611787]],"layoutPreferences":{"exclusions":[]}},"furniture":[]}
            """);
        var items=(ArrayNode)scene.get("furniture");
        for(int row=0;row<8;row++)for(int col=0;col<3;col++)items.addObject().put("id","F"+row+col).put("zoneId","ROOM").put("x",42177.15390151093+col*1400).put("y",6395.554933186317+row*2000).put("w",1400).put("h",1400).put("rotation",0).put("layoutRole","primary").put("furnitureTemplateId","desk-l");
        return scene;
    }
    @Test void measuresScreenshotGeometryInsteadOfConfiguredDefaultClearance() throws Exception {
        var scene=office();var facts=RenderLayoutContext.measure(scene);
        assertEquals(6052,facts.path("widthMm").asInt());assertEquals(20397,facts.path("depthMm").asInt());
        assertEquals(24,facts.path("primaryCount").asInt());assertEquals(8,facts.path("rowCount").asInt());assertEquals(3,facts.path("columnCount").asInt());
        var walls=facts.path("wallClearancesMm");
        assertEquals(926,walls.path("left").asInt());assertEquals(926,walls.path("right").asInt());assertEquals(498,walls.path("top").asInt());assertEquals(1626,walls.path("bottom").asInt());
        assertEquals(1,facts.path("groupGaps").size());var gap=facts.path("groupGaps").get(0);
        assertEquals("y",gap.path("axis").asText());assertEquals(600,gap.path("distanceMm").asInt());assertEquals(7,gap.path("count").asInt());
        assertTrue(facts.path("netAreaM2").asDouble()<6052*20397/1_000_000.0,"use concave net area");
        String example=System.getProperty("rsdp.render.layoutExample");
        if(example!=null) java.nio.file.Files.write(java.nio.file.Path.of(example),RenderSceneGuide.png(scene,"ISOMETRIC"));
        var prepared=context.prepare("PROJECT",scene);
        assertTrue(prepared.prompt().contains("926mm"));assertTrue(prepared.prompt().contains("600mm"));
        assertFalse(prepared.prompt().contains("42177"));
        String prompt=RenderPreviewScene.prompt(scene,"现代","ISOMETRIC","");
        assertFalse(prompt.contains("x="));assertFalse(prompt.contains("42177"));
    }
    @Test void matchesSourceRoomByStableIdAndRejectsForeignOrChangedDrawing() throws Exception {
        var scene=office();var zone=(ObjectNode)scene.get("zone");zone.put("sourceAnalysisId","ANALYSIS").put("sourceAnalysisRevision",1).put("sourceImageSha256","hash");
        var plan=new FloorPlanAnalysisResponse();plan.setProjectId("PROJECT");plan.setAnalysisId("ANALYSIS");plan.setAnalysisRevision(1);plan.setImageSha256("hash");plan.setSourceName("图纸.png");
        var room=new FloorPlanRoomResponse();room.setRoomId("ROOM");room.setLabel("原房间");plan.setRooms(List.of(room));
        when(floorPlans.getAnalysis("ANALYSIS",1)).thenReturn(plan);
        assertEquals("AVAILABLE",context.prepare("PROJECT",scene).facts().path("sourceDrawing").path("status").asText());
        plan.setImageSha256("different");assertEquals("UNAVAILABLE",context.prepare("PROJECT",scene).facts().path("sourceDrawing").path("status").asText());
        plan.setImageSha256("hash");plan.setProjectId("FOREIGN");assertEquals("UNAVAILABLE",context.prepare("PROJECT",scene).facts().path("sourceDrawing").path("status").asText());
    }
    @Test void excludesRealChairsFromDeskRowsAndKeepsMirroringAndReservations() throws Exception {
        var scene=office();((ObjectNode)scene.path("furniture").get(0)).put("mirrorX",true);
        ((ArrayNode)scene.path("zone").path("layoutPreferences").path("exclusions")).addObject().put("x",41251).put("y",24000).put("w",500).put("h",600);
        ((ArrayNode)scene.path("furniture")).addObject().put("x",42000).put("y",24000).put("w",500).put("h",500).put("rotation",90).put("layoutRole","seat");
        var facts=RenderLayoutContext.measure(scene);
        assertEquals(25,facts.path("furnitureCount").asInt());assertEquals(24,facts.path("primaryCount").asInt());
        assertEquals(8,facts.path("rowCount").asInt());assertEquals(1,facts.path("reservedAreas").size());assertEquals(1,facts.path("orientationCounts").path("0-mirrored").asInt());
    }
}
