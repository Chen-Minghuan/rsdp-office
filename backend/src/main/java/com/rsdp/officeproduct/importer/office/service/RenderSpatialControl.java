package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.awt.Shape;
import java.awt.geom.*;
import java.util.*;

/** Single source of truth for world, top-plan and isometric ground geometry. */
public final class RenderSpatialControl {
    public static final String VERSION="spatial-control-v1";
    public static final int IMAGE_WIDTH=1536,IMAGE_HEIGHT=1024;
    private RenderSpatialControl() {}
    public record Point1000(int x,int y) {}
    public record ImageGeometry(Point1000 groundCenter,List<Point1000> groundPolygon) {
        public ImageGeometry { groundPolygon=List.copyOf(groundPolygon); }
    }
    public record Frame(double scale,double tx,double ty) {
        public Point2D.Double pixel(Point2D.Double raw) { return new Point2D.Double(tx+raw.x*scale,ty+raw.y*scale); }
        public Point1000 normalized(Point2D.Double raw) {
            Point2D.Double p=pixel(raw);return new Point1000(clamp(p.x/IMAGE_WIDTH),clamp(p.y/IMAGE_HEIGHT));
        }
        private static int clamp(double value) { return (int)Math.max(0,Math.min(1000,Math.round(value*1000))); }
    }
    public record FurnitureControl(String referenceId,String furnitureId,String productReferenceId,String type,
        double centerXmm,double centerYmm,double widthMm,double depthMm,double heightMm,int rotation,
        List<Point2D.Double> worldGroundPolygon,ImageGeometry plan,ImageGeometry guide,JsonNode source) {
        public FurnitureControl { worldGroundPolygon=List.copyOf(worldGroundPolygon); }
    }
    public record RoomControl(List<Point2D.Double> worldPolygon,List<Point1000> planPolygon,List<Point1000> guidePolygon) {
        public RoomControl { worldPolygon=List.copyOf(worldPolygon);planPolygon=List.copyOf(planPolygon);guidePolygon=List.copyOf(guidePolygon); }
    }
    public record Prepared(RoomControl room,List<FurnitureControl> furniture,Frame planFrame,Frame guideFrame,double tilt,ObjectNode manifest) {
        public Prepared { furniture=List.copyOf(furniture); }
        public FurnitureControl byFurnitureId(String id) { return furniture.stream().filter(item->item.furnitureId().equals(id)).findFirst().orElseThrow(); }
        public String spacingDescription() { return manifest.path("furnitureSpacing").path("description").asText(); }
        public String prompt() {
            StringBuilder text=new StringBuilder("\n空间控制数据（程序事实，不是风格建议）：图1和图2与下列F编号一一对应。world为毫米坐标；plan1000和guide1000分别为图2平面图和图1白模中，以左上角为原点、x向右、y向下的0～1000归一化地面坐标。所有groundPolygon均为z=0的地面接触轮廓，不是家具顶部或标签位置。必须保持每件家具的地面中心、完整占地轮廓、朝向和相邻间距，不得交换同名椅子、交错、弧形排列或重新布局。\n");
            for(JsonNode item:manifest.path("layoutSemantics")) text.append(item.path("description").asText()).append('\n');
            ObjectNode compact=JsonNodeFactory.instance.objectNode();compact.set("room",manifest.get("room"));compact.set("furniture",manifest.get("furniture"));
            return text.append("SPATIAL_CONTROL_V1=").append(compact.toString()).append('\n').toString();
        }
    }
    private record Pending(JsonNode source,RenderFurnitureBindings.Binding binding,List<Point2D.Double> polygon,double height) {}

    public static Prepared prepare(JsonNode scene,RenderFurnitureBindings.Prepared bindings) {
        double tilt=.5;List<Point2D.Double> room=new ArrayList<>(),guideExtent=new ArrayList<>();
        for(JsonNode point:scene.path("zone").path("polygon")) room.add(new Point2D.Double(point.get(0).asDouble(),point.get(1).asDouble()));
        for(Point2D.Double point:room) guideExtent.add(project(point,0,tilt));
        var architecture=RenderArchitecturalOpenings.from(scene);
        for(var wall:architecture.walls()) {
            guideExtent.add(project(wall.a(),RenderArchitecturalOpenings.WALL_HEIGHT_MM,tilt));guideExtent.add(project(wall.b(),RenderArchitecturalOpenings.WALL_HEIGHT_MM,tilt));
        }
        List<Pending> pending=new ArrayList<>();
        for(JsonNode item:scene.path("furniture")) {
            var binding=bindings.byFurnitureId(item.path("id").asText());List<Point2D.Double> polygon=shapePoints(RenderPreviewScene.furnitureShape(item));double height=height(item);
            for(Point2D.Double point:polygon) { guideExtent.add(project(point,0,tilt));guideExtent.add(project(point,height,tilt)); }
            pending.add(new Pending(item,binding,polygon,height));
        }
        Frame guideFrame=frame(guideExtent,1376,864),planFrame=frame(room,1376,824);
        ObjectNode manifest=JsonNodeFactory.instance.objectNode();manifest.put("version",VERSION);
        manifest.putObject("world").put("unit","mm").put("xAxis","RIGHT").put("yAxis","DOWN").put("zAxis","UP").put("positiveRotation","CANVAS_CLOCKWISE");
        ObjectNode frames=manifest.putObject("frames");frameJson(frames.putObject("plan"),planFrame,"TOP_PLAN",null);frameJson(frames.putObject("guide"),guideFrame,"ISOMETRIC",tilt);
        ArrayNode planRoom=JsonNodeFactory.instance.arrayNode(),guideRoom=JsonNodeFactory.instance.arrayNode();
        for(Point2D.Double point:room) { addPoint(planRoom,planFrame.normalized(point));addPoint(guideRoom,guideFrame.normalized(project(point,0,tilt))); }
        ObjectNode roomJson=manifest.putObject("room");roomJson.set("planPolygon1000",planRoom);roomJson.set("guidePolygon1000",guideRoom);
        ArrayNode furnitureJson=manifest.putArray("furniture");List<FurnitureControl> furniture=new ArrayList<>();
        for(Pending entry:pending) {
            var binding=entry.binding();ImageGeometry plan=imageGeometry(entry.polygon(),binding.centerX(),binding.centerY(),planFrame,false,tilt),guide=imageGeometry(entry.polygon(),binding.centerX(),binding.centerY(),guideFrame,true,tilt);
            furniture.add(new FurnitureControl(binding.referenceId(),binding.furnitureId(),binding.productReferenceId(),binding.type(),binding.centerX(),binding.centerY(),entry.source().path("w").asDouble(),entry.source().path("h").asDouble(),entry.height(),binding.rotation(),entry.polygon(),plan,guide,entry.source()));
            ObjectNode item=furnitureJson.addObject();item.put("F",binding.referenceId()).put("furnitureId",binding.furnitureId()).put("P",binding.productReferenceId());
            item.set("worldCenterMm",roundedPoint(binding.centerX(),binding.centerY()));item.putArray("worldSizeMm").add(entry.source().path("w").asDouble()).add(entry.source().path("h").asDouble());
            item.put("rotation",binding.rotation()).put("heightMm",entry.height());item.set("worldGroundPolygon",pointsMm(entry.polygon()));item.set("plan",imageJson(plan));item.set("guide",imageJson(guide));
        }
        Prepared prepared=new Prepared(new RoomControl(room,room.stream().map(planFrame::normalized).toList(),room.stream().map(point->guideFrame.normalized(project(point,0,tilt))).toList()),furniture,planFrame,guideFrame,tilt,manifest);
        manifest.set("furnitureSpacing",RenderFurnitureSpacingTool.measure(furniture));
        manifest.set("layoutSemantics",RenderLayoutSemantics.manifest(prepared));return prepared;
    }
    public static Point2D.Double project(Point2D.Double point,double z,double tilt) { return new Point2D.Double((point.x-point.y)*.866,(point.x+point.y)*tilt-z); }
    public static double height(JsonNode item) { return item.path("heightMm").asDouble(item.path("furnitureTemplateId").asText().startsWith("chair-")?500:740); }
    private static ImageGeometry imageGeometry(List<Point2D.Double> polygon,double cx,double cy,Frame frame,boolean projected,double tilt) {
        List<Point1000> points=polygon.stream().map(point->frame.normalized(projected?project(point,0,tilt):point)).toList();
        Point2D.Double center=new Point2D.Double(cx,cy);return new ImageGeometry(frame.normalized(projected?project(center,0,tilt):center),points);
    }
    private static ObjectNode imageJson(ImageGeometry geometry) {
        ObjectNode result=JsonNodeFactory.instance.objectNode();result.putArray("groundCenter1000").add(geometry.groundCenter().x()).add(geometry.groundCenter().y());
        ArrayNode polygon=result.putArray("groundPolygon1000");geometry.groundPolygon().forEach(point->addPoint(polygon,point));return result;
    }
    private static ArrayNode pointsMm(List<Point2D.Double> points) {
        ArrayNode result=JsonNodeFactory.instance.arrayNode();points.forEach(point->result.add(roundedPoint(point.x,point.y)));return result;
    }
    private static ArrayNode roundedPoint(double x,double y) { return JsonNodeFactory.instance.arrayNode().add(Math.round(x*1000)/1000.0).add(Math.round(y*1000)/1000.0); }
    private static void addPoint(ArrayNode target,Point1000 point) { target.addArray().add(point.x()).add(point.y()); }
    private static void frameJson(ObjectNode target,Frame frame,String view,Double tilt) {
        target.put("width",IMAGE_WIDTH).put("height",IMAGE_HEIGHT).put("coordinateRange",1000).put("view",view);
        target.put("scale",frame.scale()).put("tx",frame.tx()).put("ty",frame.ty());if(tilt!=null)target.put("tilt",tilt);
    }
    private static Frame frame(List<Point2D.Double> points,double maxWidth,double maxHeight) {
        Rectangle2D bounds=path(points).getBounds2D();double scale=Math.min(maxWidth/Math.max(1,bounds.getWidth()),maxHeight/Math.max(1,bounds.getHeight()));
        double tx=(IMAGE_WIDTH-bounds.getWidth()*scale)/2-bounds.getX()*scale,ty=(IMAGE_HEIGHT-bounds.getHeight()*scale)/2-bounds.getY()*scale;
        return new Frame(scale,tx,ty);
    }
    private static Path2D path(List<Point2D.Double> points) {
        Path2D.Double result=new Path2D.Double();for(int i=0;i<points.size();i++) { Point2D.Double point=points.get(i);if(i==0)result.moveTo(point.x,point.y);else result.lineTo(point.x,point.y); }
        result.closePath();return result;
    }
    private static List<Point2D.Double> shapePoints(Shape shape) {
        List<Point2D.Double> result=new ArrayList<>();double[] coordinates=new double[6];
        for(PathIterator iterator=shape.getPathIterator(null,1.0);!iterator.isDone();iterator.next()) {
            int type=iterator.currentSegment(coordinates);
            if(type==PathIterator.SEG_MOVETO||type==PathIterator.SEG_LINETO) {
                Point2D.Double point=new Point2D.Double(coordinates[0],coordinates[1]);
                if(result.isEmpty()||result.get(result.size()-1).distance(point)>.001)result.add(point);
            }
        }
        if(result.size()>2&&result.get(0).distance(result.get(result.size()-1))<.001)result.remove(result.size()-1);return result;
    }
}
