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
    private static List<double[]> localShape(JsonNode item,double w,double h) {
        String template=item.path("moduleSnapshot").path("glyphTemplateId").asText(item.path("furnitureTemplateId").asText());
        String kind=item.path("moduleSnapshot").path("kind").asText("");
        boolean lDesk=List.of("desk-l","desk-l-rounded","desk-screen-l","desk-manager-l","desk-executive-l").contains(template)||List.of("l-desk","rounded-l-desk").contains(kind);
        List<double[]> result;
        if(lDesk) {
            double wing=Math.min(item.path("wingWidthMm").asDouble(550),Math.min(w/2,h/2)),depth=Math.min(h*.48,template.startsWith("desk-executive")?800:650);
            result=new ArrayList<>(List.of(new double[]{0,0},new double[]{w,0},new double[]{w,h},new double[]{w-wing,h},new double[]{w-wing,depth},new double[]{0,depth}));
        } else result=new ArrayList<>(List.of(new double[]{0,0},new double[]{w,0},new double[]{w,h},new double[]{0,h}));
        if(item.path("mirrorX").asBoolean()) {result.forEach(p->p[0]=w-p[0]);Collections.reverse(result);}
        return result;
    }
    /** Compatibility helper for existing diagnostic callers. */
    public static byte[] png(JsonNode scene,String view) { return render(scene,view,RenderFurnitureBindings.from(scene)).image(); }
    public static RenderedGuide render(JsonNode scene,String view,RenderFurnitureBindings.Prepared bindings) {
        double tilt="INTERIOR".equals(view)?0.32:0.5;
        List<Point2D.Double> floor=new ArrayList<>(),extent=new ArrayList<>();
        for(JsonNode p:scene.path("zone").path("polygon"))floor.add(project(p.get(0).asDouble(),p.get(1).asDouble(),0,tilt));
        extent.addAll(floor);
        List<Block> blocks=new ArrayList<>();
        for(JsonNode item:scene.path("furniture")) {
            double x=item.path("x").asDouble(),y=item.path("y").asDouble(),w=item.path("w").asDouble(),h=item.path("h").asDouble();
            double angle=Math.toRadians(item.path("rotation").asDouble()),cx=x+w/2,cy=y+h/2;
            double height=item.path("heightMm").asDouble(item.path("furnitureTemplateId").asText().startsWith("chair-")?500:740);
            List<Point2D.Double> corners=new ArrayList<>();
            for(double[] p:localShape(item,w,h)) {
                p=new double[]{x+p[0],y+p[1]};
                double px=cx+(p[0]-cx)*Math.cos(angle)-(p[1]-cy)*Math.sin(angle),py=cy+(p[0]-cx)*Math.sin(angle)+(p[1]-cy)*Math.cos(angle);
                corners.add(project(px,py,0,tilt));extent.add(project(px,py,height,tilt));
            }
            String referenceId=bindings.byFurnitureId(item.path("id").asText()).referenceId();
            blocks.add(new Block(corners,height,cx+cy,referenceId,item.path("id").asText(),project(cx,cy,height,tilt)));
        }
        Rectangle2D bounds=path(extent).getBounds2D();
        BufferedImage image=new BufferedImage(1536,1024,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        double scale=Math.min(1376/Math.max(1,bounds.getWidth()),864/Math.max(1,bounds.getHeight()));
        double tx=(1536-bounds.getWidth()*scale)/2-bounds.getX()*scale,ty=(1024-bounds.getHeight()*scale)/2-bounds.getY()*scale;
        try {
            g.setColor(Color.WHITE);g.fillRect(0,0,1536,1024);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(tx,ty);g.scale(scale,scale);
            g.setColor(new Color(235,231,223));g.fill(path(floor));
            blocks.sort(Comparator.comparingDouble(Block::depth));
            for(Block block:blocks) {
                List<Point2D.Double> top=block.corners.stream().map(p->new Point2D.Double(p.x,p.y-block.height)).toList();
                for(int i=0;i<block.corners.size();i++) {
                    int next=(i+1)%block.corners.size();
                    if(block.corners.get(next).x >= block.corners.get(i).x) continue;
                    var face=path(List.of(block.corners.get(i),block.corners.get(next),top.get(next),top.get(i)));
                    g.setColor(block.corners.get(next).y>=block.corners.get(i).y?new Color(172,184,187):new Color(192,202,203));g.fill(face);
                }
                g.setColor(new Color(221,229,228));g.fill(path(top));
            }
        } finally {g.dispose();}
        List<LabelPlacement> labels=drawLabels(image,blocks,scale,tx,ty);
        try {var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return new RenderedGuide(bytes.toByteArray(),List.copyOf(labels));}
        catch(IOException failure) {throw new com.rsdp.officeproduct.importer.common.BusinessException("空间结构参考图生成失败");}
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
