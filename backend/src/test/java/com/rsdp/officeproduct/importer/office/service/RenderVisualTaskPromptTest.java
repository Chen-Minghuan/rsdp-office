package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RenderVisualTaskPromptTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void concisePromptKeepsInventoryLayoutAndAppearanceWithoutCoordinateJson() {
        var scene=RenderSpatialControlTest.meetingScene(json);var bindings=RenderFurnitureBindings.from(scene);var spatial=RenderSpatialControl.prepare(scene,bindings);
        var products=RenderProductReferences.Prepared.empty();var physical=new RenderLayoutContext.Prepared(json.createObjectNode(),"PHYSICAL");
        var options=RenderGenerationOptions.from("CONCISE_V11","GUIDE_PLAN_PRODUCT",false,1101L,1);
        String prompt=RenderPromptCompiler.compile(scene,"现代简约","柔和自然光",bindings,physical,products,spatial,options);
        assertFalse(prompt.contains("SPATIAL_CONTROL_V1"));assertFalse(prompt.contains("worldCenterMm"));assertFalse(prompt.contains("groundPolygon1000"));assertFalse(prompt.contains("PHYSICAL"));
        assertTrue(prompt.contains("场景严格保持9件家具"));assertTrue(prompt.contains("P1外观使用数量为6件"));assertTrue(prompt.contains("P2外观使用数量为1件"));assertTrue(prompt.contains("P3外观使用数量为2件"));
        assertTrue(prompt.contains("两条彼此相对的直线排"));assertTrue(prompt.contains("3把座椅组成直线一排"));assertTrue(prompt.contains("端部1把座椅"));assertTrue(prompt.contains("柔和自然光"));
        assertEquals("B",options.group());assertEquals("visual-task-v1",options.promptVersion());
    }
    @Test void experimentOptionsOnlyAllowDefinedArms() {
        assertEquals("E",RenderGenerationOptions.baseline().group());
        assertEquals("E",RenderGenerationOptions.from(null,null,null,null,null).group());
        assertEquals("A",RenderGenerationOptions.from("TECHNICAL_V10","GUIDE_PLAN_PRODUCT",false,1101L,1).group());
        assertEquals("E",RenderGenerationOptions.from("TECHNICAL_V10","GUIDE_PLAN_PRODUCT",true,1101L,1).group());
        assertEquals("C",RenderGenerationOptions.from("CONCISE_V11","GUIDE_PRODUCT",false,null,null).group());
        assertEquals("D",RenderGenerationOptions.from("CONCISE_V11","GUIDE_PLAN_PRODUCT",true,null,null).group());
        assertThrows(BusinessException.class,()->RenderGenerationOptions.from("TECHNICAL_V10","GUIDE_PRODUCT",false,null,null));
        assertThrows(BusinessException.class,()->RenderGenerationOptions.from("TECHNICAL_V10","GUIDE_PRODUCT",true,null,null));
        assertThrows(BusinessException.class,()->RenderGenerationOptions.from("UNKNOWN","GUIDE_PLAN_PRODUCT",false,null,null));
    }
}
