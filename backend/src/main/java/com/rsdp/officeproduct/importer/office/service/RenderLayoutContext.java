package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.service.FloorPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.awt.geom.*;
import java.util.*;

/** Physical scene facts, measured from the same immutable geometry sent to rendering. */
@Service
@RequiredArgsConstructor
public class RenderLayoutContext {
    private final FloorPlanService floorPlans;
    private record Rect(double x,double y,double w,double h) {}
    public record Prepared(ObjectNode facts,String prompt) {}

    public Prepared prepare(String projectId,JsonNode scene) {
        ObjectNode facts=measure(scene);
        JsonNode zone=scene.path("zone");
        ObjectNode source=facts.putObject("sourceDrawing"); source.put("status","UNAVAILABLE");
        String analysisId=zone.path("sourceAnalysisId").asText("");
        if (!analysisId.isBlank()) {
            try {
                var plan=floorPlans.getAnalysis(analysisId,zone.path("sourceAnalysisRevision").isNumber()?zone.path("sourceAnalysisRevision").asInt():null);
                if (plan==null || plan.getProjectId()!=null && !plan.getProjectId().isBlank() && !projectId.equals(plan.getProjectId())) throw new BusinessException("来源不属于当前工作区");
                String expectedHash=zone.path("sourceImageSha256").asText("");
                if (!expectedHash.isBlank() && !expectedHash.equals(plan.getImageSha256())) throw new BusinessException("来源图纸已变化");
                var room=plan.getRooms()==null?null:plan.getRooms().stream().filter(r->zone.path("id").asText().equals(r.getRoomId())).findFirst().orElse(null);
                if(room==null) throw new BusinessException("原户型未找到同一空间 ID");
                source.put("status","AVAILABLE").put("analysisId",plan.getAnalysisId()).put("analysisRevision",plan.getAnalysisRevision());
                source.put("sourceName",plan.getSourceName()).put("imageSha256",plan.getImageSha256()).put("roomId",room.getRoomId()).put("roomLabel",room.getLabel());
                source.put("geometrySource",room.getGeometrySource()).put("referenceImageUrl",plan.getPreviewUrl()!=null?plan.getPreviewUrl():plan.getImageUrl());
                // Original geometry is evidence only. User-edited design geometry always controls the render.
                ArrayNode original=source.putArray("originalPolygon");
                if(room.getPolygon()!=null) for(var point:room.getPolygon()) {var p=original.addArray();point.forEach(p::add);}
            } catch (BusinessException unavailable) {source.put("reason","原户型来源未能可靠对应，使用当前空间设计轮廓");}
        }
        return new Prepared(facts,describe(facts));
    }

    private static Rect footprint(JsonNode item) {
        Rectangle2D b=RenderPreviewScene.furnitureShape(item).getBounds2D();
        return new Rect(b.getX(),b.getY(),b.getWidth(),b.getHeight());
    }
    public static ObjectNode measure(JsonNode scene) {
        ObjectNode result=JsonNodeFactory.instance.objectNode();result.put("version","physical-layout-v1").put("unit","mm");
        JsonNode ring=scene.path("zone").path("polygon");
        Rectangle2D bounds=RenderPreviewScene.roomPath(ring).getBounds2D();
        double area=0;for(int i=0;i<ring.size();i++){var a=ring.get(i);var b=ring.get((i+1)%ring.size());area+=a.get(0).asDouble()*b.get(1).asDouble()-b.get(0).asDouble()*a.get(1).asDouble();}
        result.put("widthMm",rounded(bounds.getWidth())).put("depthMm",rounded(bounds.getHeight())).put("netAreaM2",Math.abs(area)/2/1_000_000);
        result.put("furnitureCount",scene.path("furniture").size()).put("polygonVertexCount",ring.size());
        var actualWalls=result.putObject("wallClearancesMm");
        ArrayList<Rect> rects=new ArrayList<>(),primary=new ArrayList<>();
        var directions=result.putObject("orientationCounts");
        for(JsonNode item:scene.path("furniture")) {
            Rect rect=footprint(item);rects.add(rect);
            if(!"seat".equals(item.path("layoutRole").asText())) primary.add(rect);
            String key=String.valueOf((item.path("rotation").asInt()%360+360)%360)+(item.path("mirrorX").asBoolean()?"-mirrored":"");
            directions.put(key,directions.path(key).asInt()+1);
        }
        result.put("primaryCount",primary.size());
        result.put("rowCount",bands(primary,false)).put("columnCount",bands(primary,true));
        for(int side=0;side<4;side++) {
            boolean horizontal=side<2; int sign=side%2==0?-1:1;double best=Double.POSITIVE_INFINITY;
            for(Rect rect:rects) {
                double lo=horizontal?rect.y:rect.x, hi=lo+(horizontal?rect.h:rect.w);
                double start=horizontal?rect.x+(sign>0?rect.w:0):rect.y+(sign>0?rect.h:0);
                ArrayList<Double> samples=new ArrayList<>(List.of(lo,hi,(lo+hi)/2));
                for(JsonNode p:ring){double cross=p.get(horizontal?1:0).asDouble();if(cross>lo&&cross<hi)samples.add(cross);}
                for(double cross:samples) for(int i=0;i<ring.size();i++) {
                    JsonNode a=ring.get(i),b=ring.get((i+1)%ring.size());
                    double ac=a.get(horizontal?1:0).asDouble(),bc=b.get(horizontal?1:0).asDouble();
                    if(Math.abs(bc-ac)<0.001)continue;
                    double t=(cross-ac)/(bc-ac);if(t<-.000001||t>1.000001)continue;
                    double av=a.get(horizontal?0:1).asDouble(),bv=b.get(horizontal?0:1).asDouble(),distance=(av+(bv-av)*t-start)*sign;
                    if(distance>=-.001)best=Math.min(best,Math.max(0,distance));
                }
            }
            if(Double.isFinite(best))actualWalls.put(List.of("left","right","top","bottom").get(side),rounded(best));
        }
        ArrayNode gaps=result.putArray("groupGaps");
        Area room=new Area(RenderPreviewScene.roomPath(ring));
        for(boolean horizontal:List.of(true,false)) {
            Map<String,Double> corridors=new LinkedHashMap<>();
            for(Rect a:primary) {
                double nearest=Double.POSITIVE_INFINITY,start=horizontal?a.x+a.w:a.y+a.h;
                for(Rect b:primary) {
                    double end=horizontal?b.x:b.y,gap=end-start;if(gap<-.001)continue;
                    double lo=Math.max(horizontal?a.y:a.x,horizontal?b.y:b.x),hi=Math.min(horizontal?a.y+a.h:a.x+a.w,horizontal?b.y+b.h:b.x+b.w);
                    if(hi-lo<.001||gap>=nearest)continue;
                    if(gap>.001){double mid=(lo+hi)/2;Area line=new Area(horizontal?new Rectangle2D.Double(start,mid-.001,gap,.002):new Rectangle2D.Double(mid-.001,start,.002,gap));line.subtract(room);if(!line.isEmpty())continue;}
                    nearest=Math.max(0,gap);
                }
                if(Double.isFinite(nearest)&&nearest>.001)corridors.put(rounded(start)+":"+rounded(start+nearest),nearest);
            }
            Map<Long,Integer> counts=new TreeMap<>();corridors.values().forEach(g->counts.merge(Math.round(g),1,Integer::sum));
            counts.forEach((gap,count)->gaps.addObject().put("axis",horizontal?"x":"y").put("distanceMm",gap).put("count",count));
        }
        ArrayNode reserved=scene.path("zone").path("layoutPreferences").path("exclusions").isArray()?scene.path("zone").path("layoutPreferences").path("exclusions").deepCopy():JsonNodeFactory.instance.arrayNode();
        for(JsonNode obstacle:scene.path("zone").path("obstacles")) {
            JsonNode outer=obstacle.path("geometry").path("outer");if(!outer.isArray()||outer.size()<3)continue;
            double minX=Double.POSITIVE_INFINITY,minY=Double.POSITIVE_INFINITY,maxX=Double.NEGATIVE_INFINITY,maxY=Double.NEGATIVE_INFINITY;
            for(JsonNode point:outer){double x=point.get(0).asDouble(),y=-point.get(1).asDouble();minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);}
            ObjectNode reservedObstacle=reserved.addObject().put("id",obstacle.path("obstacleId").asText()).put("label",obstacle.path("kind").asText()).put("x",minX).put("y",minY).put("w",maxX-minX).put("h",maxY-minY);
            ArrayNode polygon=reservedObstacle.putArray("polygon");for(JsonNode point:outer)polygon.addArray().add(point.get(0).asDouble()).add(-point.get(1).asDouble());
        }
        result.set("reservedAreas",reserved);
        var architecturalOpenings=RenderArchitecturalOpenings.from(scene);
        result.put("openingsKnown",architecturalOpenings.known());
        result.set("openings",architecturalOpenings.manifest());
        result.set("unresolvedOpeningIds",architecturalOpenings.unresolved());
        return result;
    }
    private static long rounded(double value){return Math.round(value);}
    private static int bands(List<Rect> rects,boolean x) {
        ArrayList<Double> centers=new ArrayList<>();
        rects.stream().map(r->x?r.x+r.w/2:r.y+r.h/2).sorted().forEach(value->{if(centers.isEmpty()||Math.abs(centers.get(centers.size()-1)-value)>1)centers.add(value);});return centers.size();
    }
    public static String describe(JsonNode facts) {
        StringBuilder prompt=new StringBuilder("\n空间一致性约束：严格保持图1中的空间长宽比例、凹凸轮廓、家具离墙关系、组间距离、排列方式和通道留白，不重新规划布局。组内相接的家具保持紧贴，镜像和朝向以结构参考为准，已预留区域保持空白。");
        if(facts.path("openingsKnown").asBoolean()) prompt.append("图1已按人工标注画出门窗洞口：必须保留在同一墙面、同一墙内位置和相同数量，不得删除、移动或另增门窗。门窗高度为渲染示意值，不得据此声称实测高度。");
        else prompt.append("没有可靠依据的门窗和层高不作精确假设。");
        if(facts.path("unresolvedOpeningIds").size()>0) prompt.append("另有门窗净空标注未能可靠贴墙，不得在任意墙面猜测补洞。");
        prompt.append("不增加主要家具。\n");
        return prompt.toString();
    }
}
