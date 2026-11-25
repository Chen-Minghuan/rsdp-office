package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Backend projection of the same WINDOW/DOOR_CLEARANCE rules used by the 3D white model. */
public final class RenderArchitecturalOpenings {
    public static final double WALL_HEIGHT_MM=2800,WALL_THICKNESS_MM=120,DOOR_HEIGHT_MM=2100,WINDOW_BASE_MM=900,WINDOW_HEIGHT_MM=1200;
    private RenderArchitecturalOpenings() { }

    public record Opening(String obstacleId,String kind,double startMm,double endMm,double bottomMm,double topMm) { }
    public record Wall(int index,Point2D.Double a,Point2D.Double b,double length,List<Opening> openings) {
        public Wall { openings=new ArrayList<>(openings); }
        public Point2D.Double center() { return new Point2D.Double((a.x+b.x)/2,(a.y+b.y)/2); }
    }
    public record Result(List<Wall> walls,ArrayNode manifest,ArrayNode unresolved) {
        public boolean known() { return !manifest.isEmpty(); }
    }
    private record Choice(Wall wall,double start,double end,double distance,double maxDistance) { }

    public static Result from(JsonNode scene) {
        JsonNode ring=scene.path("zone").path("polygon");List<Wall> walls=new ArrayList<>();
        for(int i=0;i<ring.size();i++) {
            JsonNode a=ring.get(i),b=ring.get((i+1)%ring.size());
            Point2D.Double pa=new Point2D.Double(a.get(0).asDouble(),a.get(1).asDouble()),pb=new Point2D.Double(b.get(0).asDouble(),b.get(1).asDouble());
            if(pa.distance(pb)>=1) walls.add(new Wall(walls.size(),pa,pb,pa.distance(pb),new ArrayList<>()));
        }
        ArrayNode unresolved=JsonNodeFactory.instance.arrayNode();
        for(JsonNode obstacle:scene.path("zone").path("obstacles")) {
            String kind=obstacle.path("kind").asText(),id=obstacle.path("obstacleId").asText();
            if(!"WINDOW".equals(kind) && !"DOOR_CLEARANCE".equals(kind)) continue;
            boolean resolved="WINDOW".equals(kind)?window(walls,obstacle,id):door(walls,obstacle,id);
            if(!resolved) unresolved.add(id);
        }
        ArrayNode manifest=JsonNodeFactory.instance.arrayNode();
        for(Wall wall:walls) for(Opening opening:wall.openings()) {
            manifest.addObject().put("obstacleId",opening.obstacleId()).put("kind",opening.kind()).put("wallIndex",wall.index())
                .put("startMm",Math.round(opening.startMm())).put("endMm",Math.round(opening.endMm())).put("widthMm",Math.round(opening.endMm()-opening.startMm()))
                .put("bottomMm",Math.round(opening.bottomMm())).put("topMm",Math.round(opening.topMm())).put("heightSource","ESTIMATED").put("geometrySource","MANUAL");
        }
        return new Result(walls,manifest,unresolved);
    }

    private static boolean window(List<Wall> walls,JsonNode obstacle,String id) {
        List<Point2D.Double> line=windowLine(obstacle.path("geometry").path("outer"));if(line==null) return false;
        Choice choice=choices(walls,line,false).stream().min(Comparator.comparingDouble(Choice::distance)).orElse(null);
        if(choice==null) return false;
        double start=Math.max(.1,choice.start()),end=Math.min(choice.wall().length()-.1,choice.end());if(end-start<10) return false;
        choice.wall().openings().add(new Opening(id,"WINDOW",start,end,WINDOW_BASE_MM,WINDOW_BASE_MM+WINDOW_HEIGHT_MM));return true;
    }

    private static boolean door(List<Wall> walls,JsonNode obstacle,String id) {
        List<Point2D.Double> ring=points(obstacle.path("geometry").path("outer"));if(ring.size()<5) return false;
        Point2D.Double hinge=ring.get(0);List<Point2D.Double> ends=List.of(ring.get(1),ring.get(ring.size()-1));
        List<Choice> choices=new ArrayList<>();
        for(Point2D.Double end:ends) choices.addAll(choices(walls,List.of(hinge,end),true));
        Choice choice=choices.stream().min(Comparator.comparingDouble(Choice::distance)).orElse(null);
        if(choice==null) return false;
        choice.wall().openings().add(new Opening(id,"DOOR_CLEARANCE",choice.start(),choice.end(),0,DOOR_HEIGHT_MM));return true;
    }

    private static List<Choice> choices(List<Wall> walls,List<Point2D.Double> line,boolean door) {
        List<Choice> result=new ArrayList<>();
        for(Wall wall:walls) {
            double dx=wall.b().x-wall.a().x,dy=wall.b().y-wall.a().y,ux=dx/wall.length(),uy=dy/wall.length();
            double[] ts=new double[2],distances=new double[2];
            for(int i=0;i<2;i++) {
                Point2D.Double p=line.get(i);ts[i]=Math.max(0,Math.min(wall.length(),(p.x-wall.a().x)*ux+(p.y-wall.a().y)*uy));
                distances[i]=Math.hypot(p.x-wall.a().x-ts[i]*ux,p.y-wall.a().y-ts[i]*uy);
            }
            double start=Math.min(ts[0],ts[1]),end=Math.max(ts[0],ts[1]),max=Math.max(distances[0],distances[1]);
            if(end-start>=10 && (!door || max<=WALL_THICKNESS_MM)) result.add(new Choice(wall,start,end,distances[0]+distances[1],max));
        }
        return result;
    }

    private static List<Point2D.Double> windowLine(JsonNode ring) {
        if(ring.size()!=4) return null;
        List<Point2D.Double> p=points(ring);if(p.size()!=4) return null;
        if(p.get(0).distance(p.get(1))>=p.get(1).distance(p.get(2))) return List.of(mid(p.get(0),p.get(3)),mid(p.get(1),p.get(2)));
        return List.of(mid(p.get(0),p.get(1)),mid(p.get(3),p.get(2)));
    }
    private static Point2D.Double mid(Point2D.Double a,Point2D.Double b) { return new Point2D.Double((a.x+b.x)/2,(a.y+b.y)/2); }
    private static List<Point2D.Double> points(JsonNode input) {
        List<Point2D.Double> result=new ArrayList<>();
        for(JsonNode point:input) if(point.isArray() && point.size()==2) result.add(new Point2D.Double(point.get(0).asDouble(),-point.get(1).asDouble()));
        return result;
    }
}
