package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.service.spatial.SpatialGeometryService;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.geom.Path2D;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Frozen real model outputs, not another paid request or a mutation of a user's design.
 * Reference rectangles are provisional visual annotations, not surveyed acceptance gold. */
class RealFloorPlanContoursTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode replay(String name) throws Exception {
        return replay(name, true);
    }

    private JsonNode replay(String name, boolean includePrintedArea) throws Exception {
        return replay(name, name, includePrintedArea);
    }

    private JsonNode replay(String imageName, String fixtureName, boolean includePrintedArea) throws Exception {
        byte[] png;
        JsonNode fixture;
        try (var input = getClass().getResourceAsStream("/floorplans/" + imageName + ".png");
             var json = getClass().getResourceAsStream("/floorplans/" + fixtureName + ".json")) {
            assertNotNull(input); assertNotNull(json);
            png = input.readAllBytes(); fixture = mapper.readTree(json);
        }
        assertEquals(fixture.path("sourceImageSha256").asText(), VisionImagePreparer.sha256(png));
        var image = ImageIO.read(new java.io.ByteArrayInputStream(png));
        var page = new VisionImagePreparer.PreparedImage(png, image.getWidth(), image.getHeight(), 1, 1, "", fixture.path("sourceImageSha256").asText());
        var initial = fixture.path("initialModelOutput").deepCopy();
        if (!includePrintedArea) for (var room : initial.path("rooms")) ((com.fasterxml.jackson.databind.node.ObjectNode) room).remove("dimensionText");
        var original = initial.deepCopy();
        var result = RasterWallContours.refine(initial, page, mapper,
            new SpatialGeometryService(), fixture.path("labelLocalization"));
        assertEquals(original, initial);
        assertFalse(result.has("scaleToMm"));
        return result;
    }

    @Test
    void thinPartitionsRecoverSixRepeatedOfficesTwoMeetingRoomsAndMensToiletWithoutDeskCuts() throws Exception {
        var result = replay("thin-partitions");
        assertEquals(11, result.path("contourRefinement").path("acceptedCount").asInt(), result.toString());
        assertEquals("PARTIAL", result.path("contourRefinement").path("status").asText());
        int[] indices = {2, 5, 6, 7, 8, 9, 10, 11, 12};
        // Wall interiors, in source pixels; pillars cause small intentional differences.
        double[][] bounds = {{345,653,495,797}, {676,903,897,1114}, {905,903,1202,1114},
            {888,179,1062,427}, {1070,179,1243,427}, {1252,179,1425,427},
            {1433,179,1604,427}, {1210,903,1405,1114}, {1413,903,1604,1114}};
        double minimum = 1;
        Set<String> officeAnchors = new HashSet<>();
        for (int i = 0; i < indices.length; i++) {
            var room = result.path("rooms").get(indices[i]);
            assertTrue(room.path("contourGrounded").asBoolean(), room.toString());
            var polygon = pixelShape(room.path("polygon"), 1849, 1270);
            double iou = rasterIou(polygon, bounds[i]);
            minimum = Math.min(minimum, iou);
            assertTrue(iou >= .94, "room " + indices[i] + " provisional IoU " + iou);
            if (indices[i] >= 7) officeAnchors.add(room.path("labelAnchor").toString());
        }
        assertEquals(6, officeAnchors.size());
        // Furniture close to the facade must stay inside the room, rather than cutting narrow notches.
        assertTrue(pixelShape(result.path("rooms").get(8).path("polygon"), 1849, 1270).contains(1168, 250));
        assertTrue(pixelShape(result.path("rooms").get(11).path("polygon"), 1849, 1270).contains(1279, 1080));
        assertFalse(result.path("rooms").get(0).path("contourGrounded").asBoolean());
        assertEquals("low", result.path("rooms").get(0).path("confidence").asText());
        System.out.printf("thin-partitions: original 9 retained; minimum provisional rectangle IoU %.4f%n", minimum);
    }

    @Test
    void partitionFragmentsRecoverWholeWomensToiletAndElevatorHallButDoNotInventTeaOrWashroomRectangles() throws Exception {
        var result = replay("thin-partitions");
        var women = result.path("rooms").get(3);
        var hall = result.path("rooms").get(4);
        assertTrue(women.path("contourGrounded").asBoolean());
        assertTrue(hall.path("contourGrounded").asBoolean());
        var womenShape = pixelShape(women.path("polygon"), 1849, 1270);
        assertTrue(womenShape.contains(550, 750), "包含隔间");
        assertTrue(womenShape.contains(620, 750), "包含通行部分");
        assertFalse(womenShape.contains(490, 750), "不跨到男卫");
        assertFalse(womenShape.contains(620, 633), "墙内侧，不包含顶部墙厚");
        double womenIou = rasterIou(womenShape, new double[]{511,640,647,796});
        double hallIou = rasterIou(pixelShape(hall.path("polygon"), 1849, 1270), new double[]{331,950,667,1107});
        assertTrue(womenIou >= .97, "女卫临时参考 IoU " + womenIou);
        assertTrue(hallIou >= .97, "电梯厅临时参考 IoU " + hallIou);
        for (int index : new int[]{0,1,13,14,15,16}) {
            assertFalse(result.path("rooms").get(index).path("contourGrounded").asBoolean(), "未可靠恢复的空间 " + index);
        }
        assertEquals("low", result.path("rooms").get(1).path("confidence").asText());
        assertEquals("AREA_CHECKED_PARTITION_MERGE", result.path("contourRefinement").path("rooms").get(3).path("method").asText());
        assertFalse(result.has("scaleToMm"), "相对标注面积只校验轮廓，不替代比例校准");
        System.out.printf("thin-partitions: provisional IoU women %.4f; elevator %.4f%n", womenIou, hallIou);
    }

    @Test
    void missingPrintedAreaEvidenceDisablesWeakRecoveryAndPreservesTheNineStructuralRooms() throws Exception {
        var result = replay("thin-partitions", false);
        assertEquals(9, result.path("contourRefinement").path("acceptedCount").asInt());
        assertFalse(result.path("contourRefinement").has("pixelAreaPerPrintedM2"));
        assertFalse(result.path("rooms").get(3).path("contourGrounded").asBoolean());
        assertFalse(result.path("rooms").get(4).path("contourGrounded").asBoolean());
        assertTrue(result.path("rooms").get(5).path("contourGrounded").asBoolean());
        assertTrue(result.path("rooms").get(6).path("contourGrounded").asBoolean());
    }

    @Test
    void overflowModelCoordinatesCannotEraseTheMainOfficeOrDisableAllSixRepeatedOffices() throws Exception {
        var result = replay("thin-partitions", "overflow-open-floor", true);
        assertEquals(11, result.path("contourRefinement").path("acceptedCount").asInt());
        assertEquals(1, result.path("openSpaceRecovery").path("recoveredCount").asInt());
        assertEquals("RECOVERED_NEEDS_REVIEW", result.path("openSpaceRecovery").path("status").asText());
        var open = result.path("rooms").get(13);
        assertEquals("OPEN_OFFICE", open.path("roomType").asText());
        assertEquals("STRUCTURAL_FREE_FLOOR", open.path("contourRecovery").asText());
        assertFalse(open.path("contourGrounded").asBoolean(), "开放区补救不能算独立房名与完整封闭墙线验收");
        assertEquals("low", open.path("confidence").asText());
        var floor = pixelShape(open.path("polygon"), 1849, 1270);
        // All desk groups, plus shared internal aisles; not just an empty area around the name.
        for (double x : new double[]{800,960,1120,1280,1440,1540})
            for (double y : new double[]{550,650,750,850}) assertTrue(floor.contains(x,y), x + "," + y);
        assertFalse(floor.contains(550,750), "不包含女卫隔间");
        assertFalse(floor.contains(620,750), "不包含女卫通行部分");
        assertFalse(floor.contains(500,1050), "不包含电梯厅");
        assertFalse(floor.contains(1000,1000), "不包含中会议室");
        assertFalse(floor.contains(1000,300), "不包含顶部主管室");
        assertFalse(floor.contains(1700,650), "不越过右侧外墙");
        Set<String> anchors = new HashSet<>();
        for (int i = 7; i <= 12; i++) {
            var room = result.path("rooms").get(i);
            assertTrue(room.path("contourGrounded").asBoolean());
            anchors.add(room.path("labelAnchor").toString());
            for (var point : room.path("polygon")) for (var value : point) assertTrue(value.asDouble() >= 0 && value.asDouble() <= 1000);
        }
        assertEquals(6, anchors.size());
    }

    @Test
    void thickWallGlazedDrawingKeepsPreviouslyRecoveredRooms() throws Exception {
        var result = replay("glazed-offices");
        Set<String> names = new HashSet<>();
        for (var room : result.path("rooms")) if (room.path("contourGrounded").asBoolean()) names.add(room.path("label").asText());
        assertTrue(names.containsAll(Set.of("总经理室", "会议室", "副总经理室", "财务室")), names.toString());
        assertEquals("PARTIAL", result.path("contourRefinement").path("status").asText());
    }

    @Test
    void mixedOfficeWallFailurePreservesHistoricalRoomCandidatesInsteadOfDroppingTheFloor() throws Exception {
        var fixture = mapper.readTree(getClass().getResourceAsStream("/floorplans/mixed-office-baseline.json"));
        var result = replay("mixed-office", "mixed-office-baseline", true);
        assertEquals(fixture.path("initialModelOutput").path("rooms").size(), result.path("rooms").size());
        var names = new HashSet<String>();
        for (var room : result.path("rooms")) {
            names.add(room.path("label").asText());
            assertTrue(room.path("polygon").size() >= 3, room.toString());
        }
        assertTrue(names.containsAll(Set.of("会议室", "办公区", "展厅", "总经室", "财务室", "副总办公室", "技术部", "休闲茶室")), names.toString());
        assertFalse(result.has("regionDiscovery"), "hybrid candidates must not be replaced by anonymous wall fragments");
    }

    private Path2D pixelShape(JsonNode ring, int width, int height) {
        var shape = new Path2D.Double();
        shape.moveTo(ring.get(0).get(0).asDouble() * width / 1000, ring.get(0).get(1).asDouble() * height / 1000);
        for (var p : ring) shape.lineTo(p.get(0).asDouble() * width / 1000, p.get(1).asDouble() * height / 1000);
        shape.closePath(); return shape;
    }

    @Test
    void semanticRepeatedGroupKeepsFiveLocatedOfficesWhenOneLabelIsMissing() throws Exception {
        var fixture = mapper.readTree(getClass().getResourceAsStream("/floorplans/thin-partitions.json"));
        var initial = fixture.path("initialModelOutput").deepCopy();
        for (var room : initial.path("rooms")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) room).putArray("polygon");
            ((com.fasterxml.jackson.databind.node.ObjectNode) room).put("semanticOnly", true);
        }
        var localization = fixture.path("labelLocalization").deepCopy();
        var labels = (com.fasterxml.jackson.databind.node.ArrayNode) localization.path("labels");
        for (int i = 0; i < labels.size(); i++) if ("主管室".equals(labels.get(i).path("text").asText())) { labels.remove(i); break; }
        var result = replaySemantic(initial, localization);
        var unique = new HashSet<String>(); int offices = 0;
        for (var room : result.path("rooms")) if ("主管室".equals(room.path("label").asText()) && room.path("contourGrounded").asBoolean()) {
            offices++; unique.add(room.path("labelAnchor").toString());
        }
        assertEquals(5, offices); assertEquals(5, unique.size());
        assertEquals(10, result.path("contourRefinement").path("acceptedCount").asInt());
    }

    @Test
    void semanticRequestOmittingOneOfficeStillRetainsAllSixIndependentOccurrences() throws Exception {
        var fixture = mapper.readTree(getClass().getResourceAsStream("/floorplans/thin-partitions.json"));
        var initial = fixture.path("initialModelOutput").deepCopy();
        var rooms = (com.fasterxml.jackson.databind.node.ArrayNode) initial.path("rooms");
        for (int i = 0; i < rooms.size(); i++) if ("主管室".equals(rooms.get(i).path("label").asText())) { rooms.remove(i); break; }
        for (var room : rooms) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) room).putArray("polygon");
            ((com.fasterxml.jackson.databind.node.ObjectNode) room).put("semanticOnly", true);
        }
        var result = replaySemantic(initial, fixture.path("labelLocalization"));
        int offices = 0;
        for (var room : result.path("rooms")) if ("主管室".equals(room.path("label").asText()) && room.path("contourGrounded").asBoolean()) offices++;
        assertEquals(6, offices);
    }

    @Test
    void noLabelPositionsStillFindsUnlabelledRegionsWithoutMutatingTheSource() throws Exception {
        var initial = mapper.createObjectNode(); initial.putArray("rooms"); initial.putArray("labels");
        var before = initial.deepCopy();
        var result = replaySemantic(initial, mapper.createObjectNode().put("status", "UNAVAILABLE"));
        assertEquals(before, initial);
        assertEquals(11, result.path("regionDiscovery").path("unnamedCount").asInt());
        for (var room : result.path("rooms")) {
            assertTrue(room.path("label").isNull()); assertFalse(room.path("contourGrounded").asBoolean());
            assertEquals("low", room.path("confidence").asText());
        }
    }

    private JsonNode replaySemantic(JsonNode initial, JsonNode localization) throws Exception {
        byte[] png;
        try (var input = getClass().getResourceAsStream("/floorplans/thin-partitions.png")) { png = input.readAllBytes(); }
        var image = ImageIO.read(new java.io.ByteArrayInputStream(png));
        var page = new VisionImagePreparer.PreparedImage(png, image.getWidth(), image.getHeight(), 1, 1, "", VisionImagePreparer.sha256(png));
        return RasterWallContours.refine(initial, page, mapper, new SpatialGeometryService(), localization);
    }

    private double rasterIou(Path2D shape, double[] b) {
        var extent = shape.getBounds2D(); int intersection = 0, union = 0;
        for (int y = (int) Math.min(extent.getMinY(), b[1]); y <= Math.max(extent.getMaxY(), b[3]); y++)
            for (int x = (int) Math.min(extent.getMinX(), b[0]); x <= Math.max(extent.getMaxX(), b[2]); x++) {
                boolean actual = shape.contains(x + .5, y + .5);
                boolean expected = x + .5 >= b[0] && x + .5 < b[2] && y + .5 >= b[1] && y + .5 < b[3];
                if (actual && expected) intersection++;
                if (actual || expected) union++;
            }
        return (double) intersection / union;
    }
}
