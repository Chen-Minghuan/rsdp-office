package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class RenderFurnitureBindingsTest {
    private final ObjectMapper json=new ObjectMapper();
    private JsonNode scene() throws Exception {
        return json.readTree("""
            {"zone":{"id":"ROOM","polygon":[[0,0],[9000,0],[9000,6000],[0,6000]]},"furniture":[
              {"id":"C","label":"茶几","x":7500,"y":4500,"w":600,"h":600,"rotation":0,"productId":"SKU-2"},
              {"id":"A","label":"职员桌","x":500,"y":500,"w":1400,"h":700,"rotation":90,"productId":"SKU-1"},
              {"id":"B","label":"访客椅","x":4200,"y":2500,"w":600,"h":600,"rotation":0,"productId":"SKU-1"},
              {"id":"D","label":"沙发","x":6000,"y":1000,"w":1800,"h":800,"rotation":0,"productId":""}
            ]}
            """);
    }
    @Test void generatesStableFurnitureAndSharedProductReferences() throws Exception {
        var bindings=RenderFurnitureBindings.from(scene());
        assertEquals(4,bindings.items().size());
        assertEquals("A",bindings.items().get(0).furnitureId());
        assertEquals("F1",bindings.items().get(0).referenceId());
        assertEquals("职员桌",bindings.items().get(0).type());
        assertEquals("P1",bindings.items().get(0).productReferenceId());
        assertEquals("P1",bindings.byFurnitureId("B").productReferenceId());
        assertEquals("P2",bindings.byFurnitureId("C").productReferenceId());
        assertEquals("",bindings.byFurnitureId("D").productReferenceId());
        assertEquals("空间左侧后侧",bindings.byFurnitureId("A").location());
        assertEquals(90,bindings.byFurnitureId("A").rotation());
        String prompt=bindings.prompt();
        assertTrue(prompt.contains("共4件家具"));assertTrue(prompt.contains("职员桌"));assertTrue(prompt.contains("访客椅"));assertTrue(prompt.contains("商品外观"));assertTrue(prompt.contains("位置、朝向和占地比例"));assertFalse(prompt.contains("90度"));assertTrue(prompt.contains("F1"));assertTrue(prompt.contains("P1"));
        assertEquals(4,bindings.manifest().size());
    }
    @Test void guideContainsExactlyOneVisibleLabelPerFurniture() throws Exception {
        JsonNode scene=scene();var bindings=RenderFurnitureBindings.from(scene);
        var guide=RenderSceneGuide.render(scene,"ISOMETRIC",bindings);
        var image=ImageIO.read(new ByteArrayInputStream(guide.image()));
        String example=System.getProperty("rsdp.render.guideExample");
        if(example!=null) java.nio.file.Files.write(java.nio.file.Path.of(example),guide.image());
        assertEquals(1536,image.getWidth());assertEquals(1024,image.getHeight());
        assertEquals(4,guide.labels().size());assertEquals(4,new HashSet<>(guide.labels().stream().map(RenderSceneGuide.LabelPlacement::referenceId).toList()).size());
        var clean=RenderSceneGuide.render(scene,"ISOMETRIC",bindings,false);
        assertTrue(clean.labels().isEmpty());assertFalse(Arrays.equals(guide.image(),clean.image()));
        if(example!=null) java.nio.file.Files.write(java.nio.file.Path.of(example.replaceFirst("(?i)\\.png$","-clean.png")),clean.image());
        for(var label:guide.labels()) {
            assertTrue(label.x()>=0 && label.x()<=image.getWidth());assertTrue(label.y()>=0 && label.y()<=image.getHeight());
            assertEquals(bindings.byFurnitureId(label.furnitureId()).referenceId(),label.referenceId());
        }
    }
}
