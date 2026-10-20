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
    @Test void rejectsExclusionOverlapAndOutOfBounds() throws Exception {
        ArrayNode objects = scene();
        ((ArrayNode) objects.get(0).path("layoutPreferences").path("exclusions")).addObject().put("x", 2000).put("y", 2000).put("w", 1000).put("h", 1000);
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(objects));
        final ArrayNode outside = scene(); ((ObjectNode) outside.get(1)).put("x", 7500);
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(outside));
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
        ((ObjectNode) wall.get(1)).put("x", 50).putObject("layoutSettings").putObject("spacing").put("wallMm", 300);
        assertThrows(BusinessException.class, () -> OfficeLayoutValidationPolicy.requireConfirmedValid(wall));
    }
}
