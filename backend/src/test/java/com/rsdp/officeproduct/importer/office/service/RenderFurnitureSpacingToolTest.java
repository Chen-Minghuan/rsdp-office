package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class RenderFurnitureSpacingToolTest {
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(ints = {0, 37, 90})
    void measuresFiveRowsOfFourTouchingDesksAndThousandMillimeterRowGaps(int rotation) {
        ObjectNode scene = room();
        double radians = Math.toRadians(rotation);
        for (int row = 0; row < 5; row++) {
            for (int column = 0; column < 4; column++) {
                double x = column * 1200, y = row * 1600;
                double cx = x * Math.cos(radians) - y * Math.sin(radians);
                double cy = x * Math.sin(radians) + y * Math.cos(radians);
                scene.withArray("furniture").addObject().put("id", "D" + row + column)
                    .put("label", "职员桌").put("x", cx - 600).put("y", cy - 300).put("w", 1200).put("h", 600).put("rotation", rotation);
            }
        }
        var bindings = RenderFurnitureBindings.from(scene);
        var spatial = RenderSpatialControl.prepare(scene, bindings);
        var facts = spatial.manifest().path("furnitureSpacing");
        assertEquals(20, facts.path("modules").size());
        assertEquals(5, facts.path("joinedRows").size());
        facts.path("joinedRows").forEach(row -> assertEquals(4, row.size()));
        facts.path("modules").forEach(item -> {
            assertEquals(0, item.path("nearest").path("edgeGapMm").asDouble());
            assertEquals(1200, item.path("nearest").path("centerDistanceMm").asDouble());
        });
        int seams = 0, rowGaps = 0;
        for (var pair : facts.path("adjacent")) {
            if (pair.path("touching").asBoolean()) { assertEquals("width", pair.path("axis").asText()); seams++; }
            else { assertEquals(1000, pair.path("edgeGapMm").asDouble()); rowGaps++; }
        }
        assertEquals(15, seams);
        assertEquals(16, rowGaps, () -> facts.path("adjacent").toString());
        assertTrue(spatial.spacingDescription().contains("共5排，每排4件"));
        assertTrue(spatial.spacingDescription().contains("边到边净距0 mm"));
        assertTrue(spatial.spacingDescription().contains("1000 mm"));

        var physical = new RenderLayoutContext.Prepared(json.createObjectNode(), "");
        for (var options : new RenderGenerationOptions[]{RenderGenerationOptions.baseline(),
            RenderGenerationOptions.from("CONCISE_V11", "GUIDE_PLAN_PRODUCT", false, null, null)}) {
            String prompt = RenderPromptCompiler.compile(scene, "现代", "", bindings, physical,
                RenderProductReferences.Prepared.empty(), spatial, options);
            assertTrue(prompt.contains(spatial.spacingDescription()));
        }
    }

    @Test void measuresActualMirroredLShapeVoidAndDoesNotTreatACornerAsAJoinedRow() {
        ObjectNode scene = room();
        var items = scene.withArray("furniture");
        items.addObject().put("id", "DESK").put("label", "办公桌").put("furnitureTemplateId", "desk-l")
            .put("x", 0).put("y", 0).put("w", 2000).put("h", 1000).put("rotation", 0).put("mirrorX", true);
        items.addObject().put("id", "CHAIR").put("label", "座椅")
            .put("x", 1500).put("y", 700).put("w", 300).put("h", 200).put("rotation", 0);
        var measured = RenderSpatialControl.prepare(scene, RenderFurnitureBindings.from(scene)).manifest().path("furnitureSpacing");
        assertEquals(220, measured.path("modules").get(0).path("nearest").path("edgeGapMm").asDouble());
        assertEquals(0, measured.path("joinedRows").size());

        items.removeAll();
        items.addObject().put("id", "A").put("label", "座椅").put("x", 0).put("y", 0).put("w", 400).put("h", 400).put("rotation", 0);
        items.addObject().put("id", "B").put("label", "座椅").put("x", 400).put("y", 400).put("w", 400).put("h", 400).put("rotation", 0);
        var corner = RenderSpatialControl.prepare(scene, RenderFurnitureBindings.from(scene)).manifest().path("furnitureSpacing");
        assertEquals(0, corner.path("modules").get(0).path("nearest").path("edgeGapMm").asDouble());
        assertEquals(0, corner.path("joinedRows").size());
    }

    private ObjectNode room() {
        ObjectNode scene = json.createObjectNode();
        var polygon = scene.putObject("zone").put("id", "ROOM").put("reviewUnit", "mm").putArray("polygon");
        polygon.addArray().add(-12000).add(-12000);
        polygon.addArray().add(12000).add(-12000);
        polygon.addArray().add(12000).add(12000);
        polygon.addArray().add(-12000).add(12000);
        scene.putArray("furniture");
        return scene;
    }
}
