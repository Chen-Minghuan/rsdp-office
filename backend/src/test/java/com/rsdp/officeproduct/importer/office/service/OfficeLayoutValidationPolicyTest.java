package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OfficeLayoutValidationPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private ArrayNode scene() throws Exception {
        return (ArrayNode) json.readTree("""
            [{"id":"R","kind":"zone","zoneType":"MEETING_ROOM","reviewUnit":"mm","polygon":[[0,0],[8000,0],[8000,6000],[0,6000]],"layoutPreferences":{"targetSeats":0,"exclusions":[]}},
             {"id":"T","kind":"furniture","zoneId":"R","layoutConfirmed":true,"x":2000,"y":2000,"w":2800,"h":1200,"rotation":0,"layoutGroupId":"auto:R","layoutRole":"primary","furnitureTemplateId":"table-conference-rect"}]
            """);
    }
    @Test void acceptsValidAndTouchingFurniture() throws Exception {
        ArrayNode objects = scene();
        objects.addObject().put("id", "C").put("kind", "furniture").put("zoneId", "R").put("layoutConfirmed", true).put("x", 4800).put("y", 2000).put("w", 550).put("h", 580).put("rotation", 0).put("furnitureTemplateId", "chair-meeting");
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
    }
    @Test void acceptsFurnitureAcrossSpaceTypes() throws Exception {
        ArrayNode objects = scene(); ((ObjectNode) objects.get(1)).put("furnitureTemplateId", "desk-l");
        ((ObjectNode) objects.get(0).path("layoutPreferences")).put("targetSeats", 1);
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
    }
    @Test void acceptsGenericQuantityAndMeetingFurnitureOutsideMeetingRooms() throws Exception {
        ArrayNode objects = scene();
        ((ObjectNode) objects.get(0).path("layoutPreferences")).put("targetSeats", 1);
        ObjectNode item = (ObjectNode) objects.get(1);
        item.put("furnitureTemplateId", "cabinet-mobile");
        item.putObject("layoutSettings").put("layoutContext", "other");
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
        ((ObjectNode) objects.get(0).path("layoutPreferences")).put("targetSeats", 2);
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
        ArrayNode office = scene();
        ((ObjectNode) office.get(0)).put("zoneType", "OPEN_OFFICE");
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(office));
    }
    @Test void deskOnlyOwesSeatSideClearance() throws Exception {
        // 桌板侧靠墙可通过（前端 7b3dcd2 方向性规则的服务端对齐）：desk 座椅侧朝 S(+y)，x=0 贴左墙不再四边外扩拦截
        ArrayNode objects = scene();
        ObjectNode desk = (ObjectNode) objects.get(1);
        desk.put("furnitureTemplateId", "desk-straight").put("w", 1200).put("h", 600).put("x", 0).put("y", 2000).put("rotation", 0);
        desk.putObject("layoutSettings").putObject("spacing").put("wallMm", 800).put("aisleMm", 900);
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
        // 座椅侧净空不足（S 侧 2000+600+800=3400 超不出，改放到下边缘外）：rotation 180 → 座椅侧朝 N(-y)，y=0 贴顶墙被拦
        ((ObjectNode) objects.get(1)).put("y", 0).put("rotation", 180);
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
    }
    @Test void cornerDeskRespectsMirrorSide() throws Exception {
        // L 卡位转角校验 S+W（mirrorX=false）：贴左墙被拦；镜像后转角侧改为 E，贴左墙放行
        ArrayNode objects = scene();
        ObjectNode desk = (ObjectNode) objects.get(1);
        desk.put("furnitureTemplateId", "desk-l").put("w", 1400).put("h", 1400).put("x", 0).put("y", 2000).put("rotation", 0);
        desk.putObject("layoutSettings").putObject("spacing").put("wallMm", 560).put("aisleMm", 0);
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
        ((ObjectNode) objects.get(1)).put("mirrorX", true);
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
    }
    @Test void seatsKeepAllSideMargin() throws Exception {
        // 椅子仍四边保留 max(离墙, 组间过道)
        ArrayNode objects = scene();
        ((ObjectNode) objects.get(1)).put("layoutRole", "seat").put("furnitureTemplateId", "chair-office").put("w", 600).put("h", 600).put("x", 0).put("y", 2000);
        ((ObjectNode) objects.get(1)).putObject("layoutSettings").putObject("spacing").put("wallMm", 300).put("aisleMm", 900);
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
        ((ObjectNode) objects.get(1)).put("x", 1000);
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
    }
    @Test void rejectsExclusionOverlapAndOutOfBounds() throws Exception {
        ArrayNode objects = scene();
        ((ArrayNode) objects.get(0).path("layoutPreferences").path("exclusions")).addObject().put("x", 2000).put("y", 2000).put("w", 1000).put("h", 1000);
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
        final ArrayNode outside = scene(); ((ObjectNode) outside.get(1)).put("x", 7500);
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(outside));
    }
    @Test void rejectsConfirmedFurnitureOverlappingSpatialObstacle() throws Exception {
        ArrayNode objects = scene();
        ObjectNode obstacle = ((ObjectNode) objects.get(0)).putArray("obstacles").addObject();
        obstacle.put("obstacleId", "COL-1").put("kind", "COLUMN").put("spaceId", "R").put("excludesFloorArea", true);
        ObjectNode geometry = obstacle.putObject("geometry").put("type", "POLYGON").put("coordinateSystem", "WORLD_MM");
        geometry.putArray("outer").addArray().add(2200).add(-2200);
        geometry.withArray("outer").addArray().add(2600).add(-2200);
        geometry.withArray("outer").addArray().add(2600).add(-2600);
        geometry.withArray("outer").addArray().add(2200).add(-2600);
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
        ((ObjectNode) objects.get(1)).put("layoutConfirmed", false);
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
    }
    @Test void acceptsConfirmedFurnitureOverlappingWindowAnnotation() throws Exception {
        // 窗为标注型约束（GEOM-015）：与柱同位置同尺寸也不参与排布避让，已确认排版可保存
        ArrayNode objects = scene();
        ObjectNode obstacle = ((ObjectNode) objects.get(0)).putArray("obstacles").addObject();
        obstacle.put("obstacleId", "WIN-1").put("kind", "WINDOW").put("spaceId", "R").put("excludesFloorArea", false);
        ObjectNode geometry = obstacle.putObject("geometry").put("type", "POLYGON").put("coordinateSystem", "WORLD_MM");
        geometry.putArray("outer").addArray().add(2200).add(-2200);
        geometry.withArray("outer").addArray().add(2600).add(-2200);
        geometry.withArray("outer").addArray().add(2600).add(-2600);
        geometry.withArray("outer").addArray().add(2200).add(-2600);
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
    }
    @Test void validatesDoorSectorInsteadOfItsBoundingBox() throws Exception {
        ArrayNode objects = scene();
        ObjectNode obstacle = ((ObjectNode) objects.get(0)).putArray("obstacles").addObject();
        obstacle.put("obstacleId", "DOOR-1").put("kind", "DOOR_CLEARANCE").put("spaceId", "R").put("excludesFloorArea", false);
        ObjectNode geometry = obstacle.putObject("geometry").put("type", "POLYGON").put("coordinateSystem", "WORLD_MM");
        ArrayNode outer = geometry.putArray("outer");
        outer.addArray().add(0).add(0);
        for (int index = 0; index <= 16; index++) {
            double angle = index * Math.PI / 32;
            outer.addArray().add(1000 * Math.cos(angle)).add(-1000 * Math.sin(angle));
        }
        ObjectNode outsideArc = objects.addObject().put("id", "OUTSIDE-ARC").put("kind", "furniture").put("zoneId", "R").put("layoutConfirmed", true).put("x", 900).put("y", 500).put("w", 100).put("h", 100).put("rotation", 0).put("furnitureTemplateId", "cabinet-mobile");
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
        outsideArc.put("x", 100).put("y", 100);
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
    }
    @Test void rejectsSeatShortfallButRetainsEditableDraft() throws Exception {
        ArrayNode objects = scene(); ((ObjectNode) objects.get(0).path("layoutPreferences")).put("targetSeats", 60);
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
        ((ObjectNode) objects.get(1)).put("layoutConfirmed", false);
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
    }
    @Test void rejectsBlockedChairActivityAndInsufficientWallMargin() throws Exception {
        ArrayNode objects = scene();
        ((ObjectNode) objects.get(0).path("layoutPreferences")).put("seatClearanceMm", 400);
        objects.addObject().put("id", "C").put("kind", "furniture").put("zoneId", "R").put("layoutConfirmed", true).put("layoutRole", "seat").put("x", 3000).put("y", 3300).put("w", 550).put("h", 580).put("rotation", 0).put("furnitureTemplateId", "chair-meeting");
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
        ArrayNode wall = scene();
        // 方向性离墙（与前端 seatSideRect 对齐）：非座椅侧贴墙不拦，座椅侧（S=+y）不足才拦
        ((ObjectNode) wall.get(1)).put("x", 50).putObject("layoutSettings").putObject("spacing").put("wallMm", 300);
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(wall));
        ((ObjectNode) wall.get(1)).put("x", 2000).put("y", 6000 - 1200 - 200);
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(wall));
    }

    private ObjectNode addChannel(ArrayNode objects, String orientation, double offset, double width) {
        return ((ObjectNode) objects.get(0).path("layoutPreferences")).putArray("channels").addObject()
            .put("id", "CH-1").put("orientation", orientation).put("offsetMm", offset).put("widthMm", width);
    }
    @Test void rejectsConfirmedFurnitureBlockingChannel() throws Exception {
        ArrayNode objects = scene();
        addChannel(objects, "vertical", 3000, 900); // 禁放带 [3000,3900]×全高，桌子 2000..4800 压占
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
        ((ObjectNode) objects.get(1)).put("layoutConfirmed", false); // 草稿未确认不受通道约束
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
    }
    @Test void acceptsFurnitureBesideChannelAndRejectsClearanceExpansion() throws Exception {
        ArrayNode objects = scene();
        addChannel(objects, "vertical", 3000, 900);
        ((ObjectNode) objects.get(1)).put("x", 200); // 200..3000 与禁放带贴边，允许
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
        ArrayNode expanded = scene();
        addChannel(expanded, "vertical", 3000, 900).put("clearanceMm", 300); // 扩展带 [2700,4200]
        ((ObjectNode) expanded.get(1)).put("x", 200); // 200..3000 压占扩展带
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(expanded));
    }
    @Test void fromSecondRowChannelExemptsSavedFirstBlockOnly() throws Exception {
        ArrayNode objects = scene();
        addChannel(objects, "vertical", 3000, 900).put("fromSecondRow", true);
        ObjectNode table = (ObjectNode) objects.get(1);
        table.put("y", 1000); // 首排 y 1000..2200
        table.putObject("layoutSettings").put("firstBlockDepthMm", 2500); // 禁放带从 y=2500 起
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
        objects.addObject().put("id", "T2").put("kind", "furniture").put("zoneId", "R").put("layoutConfirmed", true).put("x", 2000).put("y", 3000).put("w", 2800).put("h", 1200).put("rotation", 0).put("furnitureTemplateId", "table-conference-rect");
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects)); // 二排起之后的家具仍不得压占
        ArrayNode noDepth = scene();
        addChannel(noDepth, "vertical", 3000, 900).put("fromSecondRow", true); // 无首排深度记录按全深从严
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(noDepth));
    }
    @Test void horizontalChannelBlocksRowsAndInvalidChannelsIgnored() throws Exception {
        ArrayNode objects = scene();
        addChannel(objects, "horizontal", 3000, 900); // 禁放带 y 3000..3900
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
        ((ObjectNode) objects.get(1)).put("y", 4200); // 4200..5400 避开通道带
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
        ArrayNode invalid = scene();
        addChannel(invalid, "vertical", 3000, 0); // 宽度非法，与前端一致跳过
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(invalid));
    }
    @Test void channelEndpointBreaksBoundTheTravelSpan() throws Exception {
        ArrayNode objects = scene();
        addChannel(objects, "vertical", 3000, 900).put("startMm", 4000); // 禁放带 [3000,3900]×y4000..6000，桌子 y 2000..3200 在留断范围外
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
        objects.addObject().put("id", "T2").put("kind", "furniture").put("zoneId", "R").put("layoutConfirmed", true).put("x", 3000).put("y", 4200).put("w", 900).put("h", 1000).put("rotation", 0).put("furnitureTemplateId", "table-conference-rect");
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects)); // 留断范围内仍不得压占
        ArrayNode ending = scene();
        addChannel(ending, "vertical", 3000, 900).put("endMm", 2500); // 禁放带 y 0..2500，桌子 y 2000..3200 压占
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(ending));
        ArrayNode horizontal = scene();
        addChannel(horizontal, "horizontal", 3000, 900).put("startMm", 5000); // 横向禁放带 x 5000..8000，桌子 x 2000..4800 不在范围
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(horizontal));
        ArrayNode inverted = scene();
        addChannel(inverted, "vertical", 3000, 900).put("startMm", 5000).put("endMm", 1000); // 起点大于终点：无有效带，与前端一致跳过
        assertDoesNotThrow(() -> OfficeLayoutValidationPolicy.requireConfirmedValid(inverted));
    }
}
