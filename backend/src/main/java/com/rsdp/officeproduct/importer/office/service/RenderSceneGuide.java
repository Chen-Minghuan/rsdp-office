package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;

/** A text-free volumetric guide. The flat plan is an audit asset, not an object in the rendered room. */
public final class RenderSceneGuide {
    private RenderSceneGuide() {}
    private record Block(List<Point2D.Double> corners,double height,double depth) {}
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
    public static byte[] png(JsonNode scene,String view) {
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
            blocks.add(new Block(corners,height,cx+cy));
        }
        Rectangle2D bounds=path(extent).getBounds2D();
        BufferedImage image=new BufferedImage(1536,1024,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();
        try {
            g.setColor(Color.WHITE);g.fillRect(0,0,1536,1024);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            double scale=Math.min(1376/Math.max(1,bounds.getWidth()),864/Math.max(1,bounds.getHeight()));
            g.translate((1536-bounds.getWidth()*scale)/2-bounds.getX()*scale,(1024-bounds.getHeight()*scale)/2-bounds.getY()*scale);g.scale(scale,scale);
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
        try {var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();}
        catch(IOException failure) {throw new BusinessException("空间结构参考图生成失败");}
    }
}
