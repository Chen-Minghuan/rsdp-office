package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.common.BusinessException;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;

/** Top-down companion to the isometric guide, using the same ground geometry and F identities. */
public final class RenderPlanGuide {
    private RenderPlanGuide() {}
    public record RenderedPlan(byte[] image) {}

    public static RenderedPlan render(RenderSpatialControl.Prepared spatial) {
        return render(spatial,true);
    }

    public static RenderedPlan render(RenderSpatialControl.Prepared spatial,boolean withLabels) {
        BufferedImage image=new BufferedImage(RenderSpatialControl.IMAGE_WIDTH,RenderSpatialControl.IMAGE_HEIGHT,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=image.createGraphics();var frame=spatial.planFrame();
        try {
            g.setColor(Color.WHITE);g.fillRect(0,0,image.getWidth(),image.getHeight());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(frame.tx(),frame.ty());g.scale(frame.scale(),frame.scale());
            Path2D room=path(spatial.room().worldPolygon());g.setColor(new Color(246,243,237));g.fill(room);
            g.setColor(new Color(55,55,55));g.setStroke(new BasicStroke((float)(5/frame.scale())));g.draw(room);
            for(var item:spatial.furniture()) {
                Path2D footprint=path(item.worldGroundPolygon());g.setColor(new Color(207,219,224));g.fill(footprint);
                g.setColor(new Color(54,80,94));g.setStroke(new BasicStroke((float)(3/frame.scale())));g.draw(footprint);
                double angle=Math.toRadians(item.rotation()),length=Math.min(item.widthMm(),item.depthMm())*.32;
                double x2=item.centerXmm()-Math.sin(angle)*length,y2=item.centerYmm()+Math.cos(angle)*length;
                g.setStroke(new BasicStroke((float)(4/frame.scale())));g.draw(new Line2D.Double(item.centerXmm(),item.centerYmm(),x2,y2));
            }
        } finally { g.dispose(); }
        if(withLabels)drawLabels(image,spatial);
        try { var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return new RenderedPlan(bytes.toByteArray()); }
        catch(Exception failure) { throw new BusinessException("平面控制图生成失败"); }
    }

    private static void drawLabels(BufferedImage image,RenderSpatialControl.Prepared spatial) {
        Graphics2D g=image.createGraphics();java.util.List<Rectangle2D> occupied=new ArrayList<>();int[][] offsets={{0,0},{0,-38},{38,-38},{-38,-38},{0,38},{48,0},{-48,0}};
        try {
            g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,20));FontMetrics metrics=g.getFontMetrics();
            for(var item:spatial.furniture()) {
                Point2D.Double anchor=spatial.planFrame().pixel(new Point2D.Double(item.centerXmm(),item.centerYmm()));
                int width=Math.max(46,metrics.stringWidth(item.referenceId())+14),height=30;Rectangle2D chosen=null;
                for(int[] offset:offsets) {
                    double x=Math.max(6,Math.min(image.getWidth()-width-6,anchor.x+offset[0]-width/2.0)),y=Math.max(6,Math.min(image.getHeight()-height-6,anchor.y+offset[1]-height/2.0));
                    Rectangle2D candidate=new Rectangle2D.Double(x,y,width,height);boolean clear=occupied.stream().noneMatch(candidate::intersects);chosen=candidate;if(clear)break;
                }
                g.setColor(new Color(35,55,65,235));g.fillRoundRect((int)chosen.getX(),(int)chosen.getY(),width,height,8,8);
                g.setColor(Color.WHITE);g.drawString(item.referenceId(),(float)chosen.getCenterX()-metrics.stringWidth(item.referenceId())/2f,(float)chosen.getCenterY()+metrics.getAscent()/2f-3);occupied.add(chosen);
            }
        } finally { g.dispose(); }
    }

    private static Path2D path(java.util.List<Point2D.Double> points) {
        Path2D.Double result=new Path2D.Double();for(int i=0;i<points.size();i++) { var point=points.get(i);if(i==0)result.moveTo(point.x,point.y);else result.lineTo(point.x,point.y); }
        result.closePath();return result;
    }
}
