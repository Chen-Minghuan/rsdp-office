package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.config.VisionProperties;
import com.rsdp.officeproduct.importer.service.spatial.SpatialGeometryService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class RasterWallContoursTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SpatialGeometryService geometry = new SpatialGeometryService();

    static BufferedImage drawing(boolean open) {
        var image = new BufferedImage(1000, 800, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try {
            g.setColor(Color.WHITE); g.fillRect(0, 0, 1000, 800);
            g.setColor(Color.DARK_GRAY); g.setStroke(new BasicStroke(8));
            g.drawPolyline(new int[]{100, 450, 450, 330, 330, 100, 100}, new int[]{100, 100, 350, 350, 450, 450, 100}, 7);
            g.drawLine(500, 100, 850, 100); g.drawLine(500, 100, 500, 700);
            if (!open) g.drawLine(850, 100, 850, 700);
            g.setStroke(new BasicStroke(2)); g.drawLine(100, 700, 900, 700);
            g.setColor(Color.WHITE); g.fillRect(444, 265, 12, 30); // door gap, collinear wall on both sides
            g.setColor(Color.DARK_GRAY); g.setStroke(new BasicStroke(1));
            g.drawRect(180, 160, 120, 70); g.drawRect(600, 300, 120, 200); // furniture must not crop the room
        } finally { g.dispose(); }
        return image;
    }

    private VisionImagePreparer.PreparedImage prepared(BufferedImage image) throws Exception {
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out);
        return new VisionImagePreparer(new VisionProperties()).prepare(new MockMultipartFile("file", "plan.png", "image/png", out.toByteArray()), 1);
    }

    private ObjectNode candidates() throws Exception {
        return (ObjectNode) mapper.readTree("""
            {"rooms":[
              {"label":"办公室甲","roomType":"EXECUTIVE_OFFICE","polygon":[[105,131],[430,131],[430,350],[105,350]]},
              {"label":"办公室乙","roomType":"EXECUTIVE_OFFICE","polygon":[[460,375],[800,375],[800,750],[460,750]]}
            ],"warnings":["来源候选"]}
            """);
    }

    @Test
    void followsWallsPreservesRecessAndIgnoresThinFurnitureWithoutChangingSourceOrScale() throws Exception {
        var source = candidates(); var before = source.deepCopy(); var page = prepared(drawing(false));
        var result = RasterWallContours.refine(source, page, mapper, geometry);
        assertEquals(2, result.path("contourRefinement").path("acceptedCount").asInt(), result.path("contourRefinement").toString());
        assertEquals(before, source);
        var left = result.path("rooms").get(0).path("polygon");
        assertTrue(left.size() >= 6, left.toString());
        double maxY = 0;
        for (var point : left) maxY = Math.max(maxY, point.get(1).asDouble() * .8);
        assertTrue(maxY > 440 && maxY < 451, left.toString());
        var right = result.path("rooms").get(1).path("polygon");
        assertTrue(right.toString().contains("875") || right.toString().contains("873"), right.toString());
        assertFalse(result.has("widthMm")); assertEquals(page.imageSha256(), prepared(drawing(false)).imageSha256());
        assertEquals("来源候选", result.path("warnings").get(0).asText());
    }

    @Test
    void facadeCrossingAReturnIsClosedWithoutBridgingWallMountedFurnitureToTheFacade() throws Exception {
        var image = drawing(false); var g = image.createGraphics();
        try {
            g.setColor(Color.WHITE); g.fillRect(494, 670, 13, 29);
            g.setColor(Color.GRAY); g.setStroke(new BasicStroke(2));
            g.drawLine(500, 670, 520, 670); g.drawLine(520, 670, 520, 730);
            g.setColor(Color.DARK_GRAY); g.drawRect(560, 650, 60, 40);
        } finally { g.dispose(); }
        var result = RasterWallContours.refine(candidates(), prepared(image), mapper, geometry);
        assertEquals("GROUNDED", result.path("contourRefinement").path("rooms").get(1).path("status").asText());
        var shape = new java.awt.geom.Path2D.Double();
        var ring = result.path("rooms").get(1).path("polygon");
        shape.moveTo(ring.get(0).get(0).asDouble(), ring.get(0).get(1).asDouble() * .8);
        for (var point : ring) shape.lineTo(point.get(0).asDouble(), point.get(1).asDouble() * .8);
        shape.closePath();
        assertTrue(shape.contains(540, 680), ring.toString());
        assertTrue(shape.contains(580, 680), ring.toString());
        assertFalse(shape.contains(510, 680), ring.toString());
    }

    @Test
    void openOrAmbiguousRoomsRetainOriginalCandidatesRatherThanFloodingAcrossThePlan() throws Exception {
        var source = candidates();
        var result = RasterWallContours.refine(source, prepared(drawing(true)), mapper, geometry);
        assertEquals(source.path("rooms").get(1).path("polygon"), result.path("rooms").get(1).path("polygon"));
        assertTrue(result.path("warnings").toString().contains("未可靠闭合"));
        // Two room labels pointing to the same enclosed region may not silently merge.
        ((ObjectNode) source.path("rooms").get(1)).set("polygon", source.path("rooms").get(0).path("polygon").deepCopy());
        result = RasterWallContours.refine(source, prepared(drawing(false)), mapper, geometry);
        assertEquals(0, result.path("contourRefinement").path("acceptedCount").asInt());
        for (int i = 0; i < 2; i++) assertEquals(source.path("rooms").get(i).path("polygon"), result.path("rooms").get(i).path("polygon"));
    }

    @Test
    void openFunctionalZonesAndUnlabelledCandidatesAreNeverPromotedToArchitecturalRooms() throws Exception {
        var source = candidates();
        ((ObjectNode) source.path("rooms").get(0)).put("roomType", "OPEN_OFFICE");
        ((ObjectNode) source.path("rooms").get(1)).putNull("label");
        var result = RasterWallContours.refine(source, prepared(drawing(false)), mapper, geometry);
        assertEquals(0, result.path("contourRefinement").path("attemptedCount").asInt());
        assertEquals(source.path("rooms"), result.path("rooms"));
    }

    @Test
    void independentLabelsRecoverSwappedPositionsAndEnclosedLoungeWithoutTypeGuessing() throws Exception {
        var source = candidates();
        var left = source.path("rooms").get(0).path("polygon").deepCopy();
        ((ObjectNode) source.path("rooms").get(0)).set("polygon", source.path("rooms").get(1).path("polygon").deepCopy());
        ((ObjectNode) source.path("rooms").get(1)).set("polygon", left);
        ((ObjectNode) source.path("rooms").get(1)).put("roomType", "LOUNGE");
        var detection = mapper.readTree("""
            {"status":"DETECTED","labels":[
              {"text":"办公室甲","bbox":[200,200,280,230]},
              {"text":"办公室乙","bbox":[600,600,680,630]}]}
            """);
        var before = source.deepCopy();
        var result = RasterWallContours.refine(source, prepared(drawing(false)), mapper, geometry, detection);
        assertEquals(2, result.path("contourRefinement").path("acceptedCount").asInt());
        assertTrue(result.path("rooms").get(0).path("polygon").get(0).get(0).asDouble() < 200);
        assertTrue(result.path("rooms").get(1).path("polygon").get(0).get(0).asDouble() > 490);
        assertEquals(before, source);
    }

    @Test
    void multipleLabelsInOneOpenRegionAndDuplicateTextAreNotMerged() throws Exception {
        var source = candidates();
        var detection = mapper.readTree("""
            {"status":"DETECTED","labels":[
              {"text":"办公室甲","bbox":[600,600,650,630]},
              {"text":"办公室乙","bbox":[700,600,750,630]}]}
            """);
        var result = RasterWallContours.refine(source, prepared(drawing(false)), mapper, geometry, detection);
        assertEquals(0, result.path("contourRefinement").path("acceptedCount").asInt());
        for (int i = 0; i < 2; i++) assertEquals(source.path("rooms").get(i).path("polygon"), result.path("rooms").get(i).path("polygon"));
        assertEquals("low", result.path("rooms").get(0).path("confidence").asText());
        detection = mapper.readTree("""
            {"status":"DETECTED","labels":[
              {"text":"办公室甲","bbox":[200,200,280,230]},
              {"text":"办公室甲","bbox":[600,600,680,630]}]}
            """);
        result = RasterWallContours.refine(source, prepared(drawing(false)), mapper, geometry, detection);
        assertEquals(0, result.path("contourRefinement").path("attemptedCount").asInt());
    }
}
