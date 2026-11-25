package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.geom.Point2D;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;

class RenderSpatialControlTest {
    private final ObjectMapper json=new ObjectMapper();

    static ObjectNode meetingScene(ObjectMapper json) {
        ObjectNode scene=json.createObjectNode();scene.putObject("zone").put("id","ROOM").put("name","会议室").put("reviewUnit","mm");
        ArrayNode room=((ObjectNode)scene.get("zone")).putArray("polygon");
        point(room,0,0);point(room,8000,0);point(room,8000,6500);point(room,0,6500);
        ArrayNode furniture=scene.putArray("furniture");
        item(furniture,"F1","P1",5700,3000,600,600,-90,"seat");
        item(furniture,"F2","P2",4500,3000,3600,1400,90,"primary");
        item(furniture,"F3","P1",5700,4200,600,600,-90,"seat");
        item(furniture,"F4","P1",3300,3000,600,600,90,"seat");
        item(furniture,"F5","P1",3300,1800,600,600,90,"seat");
        item(furniture,"F6","P1",5700,1800,600,600,-90,"seat");
        item(furniture,"F7","P1",3300,4200,600,600,90,"seat");
        item(furniture,"F8","P3",4500,600,700,700,180,"seat");
        item(furniture,"F9","P3",4500,5400,700,700,0,"seat");
        return scene;
    }
    private static void item(ArrayNode items,String id,String product,double x,double y,double w,double h,int rotation,String role) {
        items.addObject().put("id",id).put("zoneId","ROOM").put("label",id.equals("F2")?"会议桌":"椅子")
            .put("productId",product).put("x",x-w/2).put("y",y-h/2).put("w",w).put("h",h).put("rotation",rotation).put("layoutRole",role);
    }
    private static void point(ArrayNode target,double x,double y) { target.addArray().add(x).add(y); }
    private RenderSpatialControl.Prepared prepare(ObjectNode scene) { return RenderSpatialControl.prepare(scene,RenderFurnitureBindings.from(scene)); }

    @Test void normalizedGroundGeometryUsesTheSameFramesAsBothGuides() throws Exception {
        var scene=meetingScene(json);var spatial=prepare(scene);assertEquals(9,spatial.furniture().size());
        for(var item:spatial.furniture()) {
            var expectedPlan=spatial.planFrame().normalized(new Point2D.Double(item.centerXmm(),item.centerYmm()));
            var expectedGuide=spatial.guideFrame().normalized(RenderSpatialControl.project(new Point2D.Double(item.centerXmm(),item.centerYmm()),0,spatial.tilt()));
            assertEquals(expectedPlan,item.plan().groundCenter());assertEquals(expectedGuide,item.guide().groundCenter());
            assertEquals(4,item.worldGroundPolygon().size());assertEquals(4,item.plan().groundPolygon().size());
        }
        assertEquals("spatial-control-v1",spatial.manifest().path("version").asText());
        assertEquals(2,spatial.manifest().path("furniture").get(0).path("plan").path("groundCenter1000").size());
        assertTrue(spatial.manifest().path("furniture").get(0).path("plan").path("groundCenter1000").get(0).isNumber());
        assertTrue(spatial.prompt().contains("SPATIAL_CONTROL_V1="));assertTrue(spatial.prompt().contains("groundPolygon"));
        var guide=ImageIO.read(new ByteArrayInputStream(RenderSceneGuide.render(scene,"ISOMETRIC",RenderFurnitureBindings.from(scene),true,spatial).image()));
        var plan=ImageIO.read(new ByteArrayInputStream(RenderPlanGuide.render(spatial).image()));
        assertEquals(1536,guide.getWidth());assertEquals(1024,guide.getHeight());assertEquals(1536,plan.getWidth());assertEquals(1024,plan.getHeight());
    }

    @Test void negativeConcaveGeometryAndHeightDoNotChangeWorldGroundFacts() {
        ObjectNode scene=json.createObjectNode();scene.putObject("zone").put("id","R").put("reviewUnit","mm");
        ArrayNode room=((ObjectNode)scene.get("zone")).putArray("polygon");
        point(room,-6000,-4000);point(room,0,-4000);point(room,0,-2000);point(room,-2000,-2000);point(room,-2000,0);point(room,-6000,0);
        scene.putArray("furniture").addObject().put("id","F1").put("label","办公桌").put("x",-5000).put("y",-3500).put("w",1200).put("h",600).put("heightMm",740).put("rotation",90);
        var before=prepare(scene);((ObjectNode)scene.withArray("furniture").get(0)).put("heightMm",1100);var after=prepare(scene);
        assertEquals(before.furniture().get(0).worldGroundPolygon(),after.furniture().get(0).worldGroundPolygon());
        assertEquals(before.furniture().get(0).plan(),after.furniture().get(0).plan());assertEquals(6,before.room().worldPolygon().size());
        assertTrue(before.room().worldPolygon().stream().allMatch(point->point.x<=0&&point.y<=0));
    }

    @Test void lDeskMirrorAndRotationPreserveTheSameFootprintArea() {
        ObjectNode item=json.createObjectNode().put("x",0).put("y",0).put("w",2000).put("h",1000).put("rotation",0).put("furnitureTemplateId","desk-l");
        var plain=RenderPreviewScene.furnitureShape(item);assertFalse(plain.contains(100,900));assertTrue(plain.contains(1500,900));
        item.put("mirrorX",true);var mirrored=RenderPreviewScene.furnitureShape(item);assertTrue(mirrored.contains(100,900));assertFalse(mirrored.contains(1900,900));
        item.put("mirrorX",false);double area=new java.awt.geom.Area(plain).getBounds2D().getWidth()*new java.awt.geom.Area(plain).getBounds2D().getHeight();
        for(int rotation:new int[]{0,90,180,270,37}) {
            item.put("rotation",rotation);var shape=RenderPreviewScene.furnitureShape(item);assertEquals(rotation%180==0?2000:rotation%180==90?1000:shape.getBounds2D().getWidth(),shape.getBounds2D().getWidth(),.001);
            assertTrue(new java.awt.geom.Area(shape).getBounds2D().getWidth()>0);assertTrue(area>0);
        }
        item.put("rotation",90);assertEquals(1000,RenderPreviewScene.furnitureShape(item).getBounds2D().getWidth(),.001);assertEquals(2000,RenderPreviewScene.furnitureShape(item).getBounds2D().getHeight(),.001);
    }
}
