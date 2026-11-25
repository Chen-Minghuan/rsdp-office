package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RenderArchitecturalOpeningsTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode scene() {
        ObjectNode scene=json.createObjectNode();ObjectNode zone=scene.putObject("zone");
        zone.put("id","ROOM").put("reviewUnit","mm");zone.putArray("polygon").addArray().add(0).add(0);
        zone.withArray("polygon").addArray().add(6000).add(0);zone.withArray("polygon").addArray().add(6000).add(4000);zone.withArray("polygon").addArray().add(0).add(4000);
        zone.putArray("obstacles");scene.putArray("furniture");return scene;
    }
    private void point(ArrayNode ring,double x,double y) { ring.addArray().add(x).add(-y); }
    private void window(ObjectNode scene) {
        ObjectNode obstacle=scene.withArray("/zone/obstacles").addObject().put("obstacleId","W1").put("kind","WINDOW");
        ArrayNode ring=obstacle.putObject("geometry").putArray("outer");
        point(ring,1000,3999.5);point(ring,3000,3999.5);point(ring,3000,4000.5);point(ring,1000,4000.5);
    }
    private void door(ObjectNode scene,double hingeX,double hingeY) {
        ObjectNode obstacle=scene.withArray("/zone/obstacles").addObject().put("obstacleId","D1").put("kind","DOOR_CLEARANCE");
        ArrayNode ring=obstacle.putObject("geometry").putArray("outer");point(ring,hingeX,hingeY);
        for(int i=0;i<=16;i++) { double angle=i*Math.PI/32;point(ring,hingeX+1000*Math.cos(angle),hingeY+1000*Math.sin(angle)); }
    }
    @Test void projectsMarkedWindowsAndDoorsOntoTheSameWallsAsWhiteModel() throws Exception {
        var scene=scene();window(scene);door(scene,0,1000);
        var result=RenderArchitecturalOpenings.from(scene);
        assertEquals(2,result.manifest().size());assertTrue(result.known());assertEquals(0,result.unresolved().size());
        assertEquals("WINDOW",result.manifest().get(0).path("kind").asText());assertEquals(2,result.manifest().get(0).path("wallIndex").asInt());
        assertEquals(2000,result.manifest().get(0).path("widthMm").asInt());assertEquals(900,result.manifest().get(0).path("bottomMm").asInt());
        assertEquals("DOOR_CLEARANCE",result.manifest().get(1).path("kind").asText());assertEquals(3,result.manifest().get(1).path("wallIndex").asInt());
        assertEquals("ESTIMATED",result.manifest().get(1).path("heightSource").asText());
        var facts=RenderLayoutContext.measure(scene);assertTrue(facts.path("openingsKnown").asBoolean());assertEquals(2,facts.path("openings").size());
        assertTrue(RenderLayoutContext.describe(facts).contains("必须保留在同一墙面"));assertFalse(RenderLayoutContext.describe(facts).contains("没有可靠依据的门窗"));
        byte[] without=RenderSceneGuide.png(this.scene(),"ISOMETRIC"),with=RenderSceneGuide.png(scene,"ISOMETRIC");assertFalse(Arrays.equals(without,with));
        String example=System.getProperty("rsdp.render.openingsExample");if(example!=null) java.nio.file.Files.write(java.nio.file.Path.of(example),with);
    }
    @Test void doesNotInventDoorOnArbitraryWallForUnresolvedClearance() {
        var scene=scene();door(scene,2000,1500);var result=RenderArchitecturalOpenings.from(scene);
        assertFalse(result.known());assertEquals("D1",result.unresolved().get(0).asText());assertEquals(1,result.unresolved().size());
        var facts=RenderLayoutContext.measure(scene);assertFalse(facts.path("openingsKnown").asBoolean());
        assertTrue(RenderLayoutContext.describe(facts).contains("不得在任意墙面猜测补洞"));
    }
}
