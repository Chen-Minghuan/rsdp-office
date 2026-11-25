package com.rsdp.officeproduct.importer.office.service;

import java.awt.Shape;
import java.awt.geom.*;
import java.util.*;

/** Deterministic geometry measurements; no vision-model status is accepted as a numeric measurement. */
public final class RenderGeometryMetrics {
    private RenderGeometryMetrics() {}

    public static double centerError(RenderSpatialControl.Point1000 expected,RenderSpatialControl.Point1000 observed,double roomDiagonal) {
        return Math.hypot(expected.x()-observed.x(),expected.y()-observed.y())/Math.max(roomDiagonal,1);
    }

    public static double roomDiagonal(List<RenderSpatialControl.Point1000> room) {
        if(room==null||room.isEmpty())return 1;
        int minX=room.stream().mapToInt(RenderSpatialControl.Point1000::x).min().orElse(0),maxX=room.stream().mapToInt(RenderSpatialControl.Point1000::x).max().orElse(1);
        int minY=room.stream().mapToInt(RenderSpatialControl.Point1000::y).min().orElse(0),maxY=room.stream().mapToInt(RenderSpatialControl.Point1000::y).max().orElse(1);
        return Math.hypot(maxX-minX,maxY-minY);
    }

    public static double iou(List<RenderSpatialControl.Point1000> expected,List<RenderSpatialControl.Point1000> observed) {
        if(!valid(expected)||!valid(observed))return Double.NaN;
        Area a=new Area(polygon(expected)),b=new Area(polygon(observed));
        double areaA=area(a),areaB=area(b);if(areaA<.0001||areaB<.0001)return Double.NaN;
        Area intersection=new Area(a);intersection.intersect(b);Area union=new Area(a);union.add(b);
        double divisor=area(union);return divisor<.0001?Double.NaN:Math.max(0,Math.min(1,area(intersection)/divisor));
    }

    public static boolean valid(List<RenderSpatialControl.Point1000> polygon) {
        if(polygon==null||polygon.size()<3)return false;
        return polygon.stream().allMatch(point->point!=null&&point.x()>=0&&point.x()<=1000&&point.y()>=0&&point.y()<=1000);
    }

    /** Minimum-cost one-to-one assignment. Returned observed indexes may be -1 for missing detections. */
    public static int[] assign(double[][] cost) {
        int rows=cost.length;if(rows==0)return new int[0];
        int columns=cost[0].length,size=Math.max(rows,columns);double[][] a=new double[size+1][size+1];
        for(int i=1;i<=size;i++)Arrays.fill(a[i],1000);
        for(int i=1;i<=rows;i++)for(int j=1;j<=columns;j++)a[i][j]=cost[i-1][j-1];
        double[] u=new double[size+1],v=new double[size+1];int[] p=new int[size+1],way=new int[size+1];
        for(int i=1;i<=size;i++) {
            p[0]=i;int j0=0;double[] minv=new double[size+1];Arrays.fill(minv,Double.POSITIVE_INFINITY);boolean[] used=new boolean[size+1];
            do {
                used[j0]=true;int i0=p[j0],j1=0;double delta=Double.POSITIVE_INFINITY;
                for(int j=1;j<=size;j++)if(!used[j]) {
                    double cur=a[i0][j]-u[i0]-v[j];if(cur<minv[j]) { minv[j]=cur;way[j]=j0; }
                    if(minv[j]<delta) { delta=minv[j];j1=j; }
                }
                for(int j=0;j<=size;j++)if(used[j]) { u[p[j]]+=delta;v[j]-=delta; }else minv[j]-=delta;
                j0=j1;
            } while(p[j0]!=0);
            do { int j1=way[j0];p[j0]=p[j1];j0=j1; } while(j0!=0);
        }
        int[] result=new int[rows];Arrays.fill(result,-1);
        for(int j=1;j<=columns;j++)if(p[j]>=1&&p[j]<=rows)result[p[j]-1]=j-1;
        return result;
    }

    private static Path2D polygon(List<RenderSpatialControl.Point1000> points) {
        Path2D.Double path=new Path2D.Double();for(int i=0;i<points.size();i++) { var point=points.get(i);if(i==0)path.moveTo(point.x(),point.y());else path.lineTo(point.x(),point.y()); }
        path.closePath();return path;
    }

    private static double area(Shape shape) {
        double sum=0,firstX=0,firstY=0,x=0,y=0;double[] p=new double[6];
        for(PathIterator it=shape.getPathIterator(null,.05);!it.isDone();it.next()) {
            int type=it.currentSegment(p);
            if(type==PathIterator.SEG_MOVETO) { firstX=x=p[0];firstY=y=p[1]; }
            else if(type==PathIterator.SEG_LINETO) { sum+=x*p[1]-p[0]*y;x=p[0];y=p[1]; }
            else if(type==PathIterator.SEG_CLOSE)sum+=x*firstY-firstX*y;
        }
        return Math.abs(sum)/2;
    }
}
