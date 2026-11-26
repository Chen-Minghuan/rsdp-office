package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;

/** A 45-degree volumetric guide with temporary furniture reference IDs. */
public final class RenderSceneGuide {
    private RenderSceneGuide() {}
    private record Block(List<Point2D.Double> corners,double height,double depth,String referenceId,String furnitureId,Point2D.Double labelAnchor) {}
    public record LabelPlacement(String referenceId,String furnitureId,double x,double y) {}
    public record RenderedGuide(byte[] image,List<LabelPlacement> labels) {}
    private static Point2D.Double project(double x,double y,double z,double tilt) {
        return new Point2D.Double((x-y)*0.866,(x+y)*tilt-z);
    }
    private static Path2D path(List<Point2D.Double> points) {
        Path2D result=new Path2D.Double();
        for(int i=0;i<points.size();i++) {var p=points.get(i); if(i==0)result.moveTo(p.x,p.y);else result.lineTo(p.x,p.y);}
        result.closePath(); return result;
    }
    /** Compatibility helper for existing diagnostic callers. */
    public static byte[] png(JsonNode scene,String view) { return render(scene,view,RenderFurnitureBindings.from(scene)).image(); }
    public static RenderedGuide render(JsonNode scene,String view,RenderFurnitureBindings.Prepared bindings) { return render(scene,view,bindings,true); }
    public static RenderedGuide render(JsonNode scene,String view,RenderFurnitureBindings.Prepared bindings,boolean withLabels) {
        return render(scene,view,bindings,withLabels,RenderSpatialControl.prepare(scene,bindings));
    }
    public static RenderedGuide render(JsonNode scene,String view,RenderFurnitureBindings.Prepared bindings,boolean withLabels,RenderSpatialControl.Prepared spatial) {
        double tilt=spatial.tilt();
        List<Point2D.Double> floor=spatial.room().worldPolygon().stream().map(point->RenderSpatialControl.project(point,0,tilt)).toList();
        var architecture=RenderArchitecturalOpenings.from(scene);
        List<Double> wallDepths=architecture.walls().stream().map(wall->wall.center().x+wall.center().y).sorted().toList();
        double cutawayDepth=wallDepths.isEmpty()?0:wallDepths.size()%2==0?(wallDepths.get(wallDepths.size()/2-1)+wallDepths.get(wallDepths.size()/2))/2:wallDepths.get(wallDepths.size()/2);
        List<Block> blocks=new ArrayList<>();
        for(var item:spatial.furniture()) {
            List<Point2D.Double> corners=item.worldGroundPolygon().stream().map(point->RenderSpatialControl.project(point,0,tilt)).toList();
            Point2D.Double center=new Point2D.Double(item.centerXmm(),item.centerYmm());
            blocks.add(new Block(corners,item.heightMm(),item.centerXmm()+item.centerYmm(),item.referenceId(),item.furnitureId(),RenderSpatialControl.project(center,item.heightMm(),tilt)));
        }
        BufferedImage image=new BufferedImage(1536,1024,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        double scale=spatial.guideFrame().scale(),tx=spatial.guideFrame().tx(),ty=spatial.guideFrame().ty();
        try {
            g.setColor(Color.WHITE);g.fillRect(0,0,1536,1024);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(tx,ty);g.scale(scale,scale);
            g.setColor(new Color(235,231,223));g.fill(path(floor));
            g.setColor(new Color(94,126,135));g.setStroke(new BasicStroke((float)(5/scale)));
            for(Block block:blocks)g.draw(path(block.corners()));
            drawArchitecture(g,architecture,tilt,cutawayDepth);
            blocks.sort(Comparator.comparingDouble(Block::depth));
            for(Block block:blocks) {
                List<Point2D.Double> top=block.corners.stream().map(p->new Point2D.Double(p.x,p.y-block.height)).toList();
                for(int i=0;i<block.corners.size();i++) {
                    int next=(i+1)%block.corners.size();
                    if(block.corners.get(next).x >= block.corners.get(i).x) continue;
                    var face=path(List.of(block.corners.get(i),block.corners.get(next),top.get(next),top.get(i)));
                    g.setColor(block.corners.get(next).y>=block.corners.get(i).y?new Color(172,184,187):new Color(192,202,203));g.fill(face);
                }
                var tabletop=path(top);
                g.setColor(new Color(221,229,228));g.fill(tabletop);
                // Touching modules share an edge. Show its seam without enlarging their gaps.
                g.setColor(new Color(142,158,160));g.setStroke(new BasicStroke((float)(1.35/scale)));g.draw(tabletop);
            }
        } finally {g.dispose();}
        List<LabelPlacement> labels=withLabels?drawLabels(image,blocks,scale,tx,ty):List.of();
        try {var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return new RenderedGuide(bytes.toByteArray(),List.copyOf(labels));}
        catch(IOException failure) {throw new com.rsdp.officeproduct.importer.common.BusinessException("空间结构参考图生成失败");}
    }
    private static void drawArchitecture(Graphics2D g,RenderArchitecturalOpenings.Result architecture,double tilt,double cutawayDepth) {
        List<RenderArchitecturalOpenings.Wall> walls=new ArrayList<>(architecture.walls());
        walls.sort(Comparator.comparingDouble(wall->wall.center().x+wall.center().y));
        for(var wall:walls) {
            boolean cutaway=wall.center().x+wall.center().y>cutawayDepth;
            var openings=wall.openings().stream().sorted(Comparator.comparingDouble(RenderArchitecturalOpenings.Opening::startMm)).toList();
            double cursor=0,fullHeight=cutaway?120:RenderArchitecturalOpenings.WALL_HEIGHT_MM;
            for(var opening:openings) {
                wallRange(g,wall,cursor,opening.startMm(),0,fullHeight,tilt,cutaway);
                if(!cutaway) {
                    wallRange(g,wall,opening.startMm(),opening.endMm(),0,opening.bottomMm(),tilt,false);
                    wallRange(g,wall,opening.startMm(),opening.endMm(),opening.topMm(),RenderArchitecturalOpenings.WALL_HEIGHT_MM,tilt,false);
                }
                opening(g,wall,opening,tilt);cursor=Math.max(cursor,opening.endMm());
            }
            wallRange(g,wall,cursor,wall.length(),0,fullHeight,tilt,cutaway);
        }
    }
    private static void wallRange(Graphics2D g,RenderArchitecturalOpenings.Wall wall,double start,double end,double bottom,double top,double tilt,boolean cutaway) {
        if(end-start<=.1 || top-bottom<=.1) return;
        Point2D.Double a=at(wall,start),b=at(wall,end);
        var face=path(List.of(project(a.x,a.y,bottom,tilt),project(b.x,b.y,bottom,tilt),project(b.x,b.y,top,tilt),project(a.x,a.y,top,tilt)));
        g.setColor(cutaway?new Color(210,211,205):new Color(214,216,211));g.fill(face);
        g.setColor(new Color(177,183,180));g.setStroke(new BasicStroke(18));g.draw(face);
    }
    private static void opening(Graphics2D g,RenderArchitecturalOpenings.Wall wall,RenderArchitecturalOpenings.Opening opening,double tilt) {
        Point2D.Double a=at(wall,opening.startMm()),b=at(wall,opening.endMm());
        Point2D.Double ab=project(a.x,a.y,opening.bottomMm(),tilt),bb=project(b.x,b.y,opening.bottomMm(),tilt);
        Point2D.Double at=project(a.x,a.y,opening.topMm(),tilt),bt=project(b.x,b.y,opening.topMm(),tilt);
        if("WINDOW".equals(opening.kind())) {
            var pane=path(List.of(ab,bb,bt,at));g.setColor(new Color(169,211,222,190));g.fill(pane);
            g.setColor(new Color(92,137,150));g.setStroke(new BasicStroke(24));g.draw(pane);
        } else {
            g.setColor(new Color(137,122,105));g.setStroke(new BasicStroke(28));
            g.draw(new Line2D.Double(ab,at));g.draw(new Line2D.Double(at,bt));g.draw(new Line2D.Double(bt,bb));
        }
    }
    private static Point2D.Double at(RenderArchitecturalOpenings.Wall wall,double distance) {
        double t=distance/wall.length();return new Point2D.Double(wall.a().x+(wall.b().x-wall.a().x)*t,wall.a().y+(wall.b().y-wall.a().y)*t);
    }
    private static List<LabelPlacement> drawLabels(BufferedImage image,List<Block> blocks,double scale,double tx,double ty) {
        Graphics2D g=image.createGraphics();List<Rectangle2D> occupied=new ArrayList<>();List<LabelPlacement> result=new ArrayList<>();
        int[][] offsets={{0,0},{0,-44},{44,-44},{-44,-44},{0,44},{64,0},{-64,0}};
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,24));FontMetrics metrics=g.getFontMetrics();
            for(Block block:blocks) {
                double anchorX=tx+block.labelAnchor().x*scale,anchorY=ty+block.labelAnchor().y*scale;
                int width=Math.max(52,metrics.stringWidth(block.referenceId())+18),height=34;
                Rectangle2D chosen=null;int chosenOffset=0;double bestScore=Double.MAX_VALUE;
                for(int attempt=0;attempt<offsets.length;attempt++) {
                    double x=Math.max(8,Math.min(image.getWidth()-width-8,anchorX+offsets[attempt][0]-width/2.0));
                    double y=Math.max(8,Math.min(image.getHeight()-height-8,anchorY+offsets[attempt][1]-height/2.0));
                    Rectangle2D candidate=new Rectangle2D.Double(x,y,width,height);
                    double overlap=0;
                    for(Rectangle2D other:occupied) if(candidate.intersects(other)) {
                        Rectangle2D intersection=candidate.createIntersection(other);overlap+=intersection.getWidth()*intersection.getHeight();
                    }
                    double score=overlap*1000+Math.hypot(offsets[attempt][0],offsets[attempt][1]);
                    if(score<bestScore) {chosen=candidate;chosenOffset=attempt;bestScore=score;}
                    if(overlap==0) break;
                }
                if(chosenOffset!=0) {g.setColor(new Color(45,65,72));g.setStroke(new BasicStroke(2));g.drawLine((int)Math.round(anchorX),(int)Math.round(anchorY),(int)Math.round(chosen.getCenterX()),(int)Math.round(chosen.getCenterY()));}
                g.setColor(new Color(35,55,65,235));g.fillRoundRect((int)chosen.getX(),(int)chosen.getY(),width,height,10,10);
                g.setColor(Color.WHITE);g.drawString(block.referenceId(),(float)chosen.getCenterX()-metrics.stringWidth(block.referenceId())/2f,(float)chosen.getCenterY()+metrics.getAscent()/2f-3);
                occupied.add(chosen);
                result.add(new LabelPlacement(block.referenceId(),block.furnitureId(),chosen.getCenterX(),chosen.getCenterY()));
            }
        } finally {g.dispose();}
        return result;
    }
}
