package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RenderLayoutSemanticsTest {
    private final ObjectMapper json=new ObjectMapper();

    @Test void meetingRoomProducesTwoStraightRowsAndCenteredHeadChairs() {
        var scene=RenderSpatialControlTest.meetingScene(json);var spatial=RenderSpatialControl.prepare(scene,RenderFurnitureBindings.from(scene));
        String text=spatial.manifest().path("layoutSemantics").toString();
        assertTrue(text.contains("两条彼此相对的直线排"),text);
        assertTrue(text.contains("F5→F4→F7"),text);assertTrue(text.contains("F6→F1→F3"),text);
        assertTrue(text.contains("相邻中心间距依次1200、1200mm"),text);
        assertTrue(text.contains("中心共线，组成直线一排"),text);
        assertTrue(text.contains("F8位于F2后侧端部"),text);assertTrue(text.contains("F9位于F2前侧端部"),text);
        assertTrue(text.contains("短边中心线偏差0mm"),text);
        assertTrue(text.contains("不得交错、弧形、散开或改成随意角度"),text);
        assertTrue(spatial.prompt().contains("两排均与F2的长边平行"));
        assertTrue(text.contains("SIDE_ROW"));assertTrue(text.contains("HEAD_SEAT"));assertTrue(text.contains("productRefs"));
    }

    @Test void irregularFurnitureFallsBackToRelativePositionInsteadOfInventingRows() {
        var scene=RenderSpatialControlTest.meetingScene(json);var items=scene.withArray("furniture");
        for(int i=0;i<items.size();i++) if(!"F2".equals(items.get(i).path("id").asText())) ((com.fasterxml.jackson.databind.node.ObjectNode)items.get(i)).put("layoutRole","other");
        var spatial=RenderSpatialControl.prepare(scene,RenderFurnitureBindings.from(scene));String text=spatial.manifest().path("layoutSemantics").toString();
        assertFalse(text.contains("两条彼此相对的直线排"));assertTrue(text.contains("严格保持图2"));
    }

    @Test void legacyChairsWithoutLayoutRoleStillProduceGeometryDerivedRows() {
        var scene=RenderSpatialControlTest.meetingScene(json);scene.withArray("furniture").forEach(item->((com.fasterxml.jackson.databind.node.ObjectNode)item).remove("layoutRole"));
        var spatial=RenderSpatialControl.prepare(scene,RenderFurnitureBindings.from(scene));String text=spatial.manifest().path("layoutSemantics").toString();
        assertTrue(text.contains("两条彼此相对的直线排"),text);assertTrue(text.contains("F5→F4→F7"),text);assertTrue(text.contains("F6→F1→F3"),text);
    }
}
