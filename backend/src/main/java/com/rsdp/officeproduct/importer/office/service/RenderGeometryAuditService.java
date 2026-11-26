package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.RenderProperties;
import com.rsdp.officeproduct.importer.service.QwenVisionClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** Non-authoritative visual detection followed by deterministic one-to-one geometry measurements. */
@Service
@RequiredArgsConstructor
public class RenderGeometryAuditService {
    public static final String VERSION="geometry-audit-v1";
    private final QwenVisionClient vision;
    private final RenderProperties properties;
    private record Observation(String id,String category,RenderSpatialControl.Point1000 center,List<RenderSpatialControl.Point1000> polygon,double confidence,String visibility) {}

    public ObjectNode audit(byte[] result,byte[] guide,byte[] plan,RenderSpatialControl.Prepared spatial) {
        String prompt="""
            你是独立的室内效果图几何检测员。图1是生成结果，图2是无文字45度白模，图3是同一布局的无文字平面控制图。
            先只检查图1是否只有一个完整45度室内场景，是否混入商品板、平面图、编号、文字、拼贴或分栏，相机和房间是否仍与图2一致。
            再只根据图1的实际可见像素，穷举其中每一个家具实例，包括无编号家具、重复家具、参考布局之外的新增家具和局部遮挡家具。
            不得根据图2或图3推算图1的家具数量，不得复制参考图的坐标，不得为了符合任何预期而省略额外实例或编造缺失实例。
            图2和图3只能帮助理解相机、地面和遮挡，不能作为图1的检测结果。后续参考图的存在本身不属于图1的构图错误。
            返回图1每个可见实例的地面中心和groundPolygon1000，不包含任何参考板中的商品。
            坐标以图1左上角为原点，x向右、y向下，归一化到0～1000。groundPolygon是z=0的接地轮廓，不是椅背顶部或整体包围盒。
            不需要猜测F编号，也没有期望数量；程序会独立一对一匹配。看不清返回OCCLUDED或UNCERTAIN，不得为凑数量编造实例。
            仅返回JSON：
            {"composition":{"singleScene":true,"containsReferenceBoard":false,"containsPlan":false,"containsLabels":false,"containsCollage":false,"cameraMatchesGuide":true,"status":"PASS|FAIL|UNCERTAIN","reason":""},
             "room":{"guidePolygon1000":[[0,0]],"status":"PASS|FAIL|UNCERTAIN","reason":""},
             "observations":[{"detectionId":"D1","category":"CHAIR|TABLE|DESK|SOFA|STORAGE|OTHER","groundCenter1000":[0,0],"groundPolygon1000":[[0,0],[1,0],[1,1]],"confidence":0.9,"visibility":"VISIBLE|OCCLUDED|UNCERTAIN"}]}
            """;
        return normalize(vision.auditRender(prompt,List.of(result,guide,plan)),spatial);
    }

    ObjectNode normalize(JsonNode raw,RenderSpatialControl.Prepared spatial) {
        if(raw==null||!raw.isObject())throw new BusinessException("位置几何审核没有返回有效对象");
        ObjectNode result=JsonNodeFactory.instance.objectNode();result.put("version",VERSION);
        JsonNode sourceComposition=raw.path("composition");ObjectNode composition=result.putObject("composition");
        boolean compositionIssue=!sourceComposition.path("singleScene").asBoolean(true)||sourceComposition.path("containsReferenceBoard").asBoolean()||sourceComposition.path("containsPlan").asBoolean()
            ||sourceComposition.path("containsLabels").asBoolean()||sourceComposition.path("containsCollage").asBoolean()||!sourceComposition.path("cameraMatchesGuide").asBoolean(true);
        for(String field:List.of("singleScene","containsReferenceBoard","containsPlan","containsLabels","containsCollage","cameraMatchesGuide"))composition.put(field,sourceComposition.path(field).asBoolean(!List.of("containsReferenceBoard","containsPlan","containsLabels","containsCollage").contains(field)));
        String compositionStatus=compositionIssue?"FAIL":status(sourceComposition.path("status").asText());composition.put("status",compositionStatus).put("reason",reason(sourceComposition.path("reason").asText("")));
        List<String> overall=new ArrayList<>();overall.add(compositionStatus);
        ObjectNode room=result.putObject("room");List<RenderSpatialControl.Point1000> observedRoom=points(raw.path("room").path("guidePolygon1000"));
        double roomIou=RenderGeometryMetrics.iou(spatial.room().guidePolygon(),observedRoom);String roomStatus=status(raw.path("room").path("status").asText());
        if(Double.isNaN(roomIou))roomStatus="UNCERTAIN";else if(roomIou<properties.getGeometryAudit().getRoomIouPass())roomStatus="FAIL";
        room.put("status",roomStatus).put("reason",reason(raw.path("room").path("reason").asText("")));if(!Double.isNaN(roomIou))room.put("iou",roomIou);overall.add(roomStatus);
        List<Observation> observations=new ArrayList<>();ArrayNode observationJson=result.putArray("observations");Set<String> detectionIds=new HashSet<>();
        for(JsonNode item:raw.path("observations")) {
            List<RenderSpatialControl.Point1000> polygon=points(item.path("groundPolygon1000"));if(!RenderGeometryMetrics.valid(polygon))continue;
            String id=item.path("detectionId").asText("").trim();if(id.isBlank()||!detectionIds.add(id))id="D"+(detectionIds.size()+1);detectionIds.add(id);
            RenderSpatialControl.Point1000 center=point(item.path("groundCenter1000"));if(center==null)center=centroid(polygon);
            double confidence=item.path("confidence").asDouble(0);String visibility=item.path("visibility").asText("UNCERTAIN");
            observations.add(new Observation(id,item.path("category").asText("OTHER").toUpperCase(Locale.ROOT),center,polygon,confidence,visibility));
            ObjectNode saved=observationJson.addObject();saved.put("detectionId",id).put("category",observations.get(observations.size()-1).category()).put("confidence",confidence).put("visibility",visibility);
            saved.putArray("groundCenter1000").add(center.x()).add(center.y());ArrayNode savedPolygon=saved.putArray("groundPolygon1000");polygon.forEach(point->savedPolygon.addArray().add(point.x()).add(point.y()));
        }
        int expectedCount=spatial.furniture().size(),observedCount=observations.size();double diagonal=RenderGeometryMetrics.roomDiagonal(spatial.room().guidePolygon());
        double[][] costs=new double[expectedCount][observedCount];
        for(int i=0;i<expectedCount;i++)for(int j=0;j<observedCount;j++) {
            var expected=spatial.furniture().get(i);var observed=observations.get(j);double error=RenderGeometryMetrics.centerError(expected.guide().groundCenter(),observed.center(),diagonal);
            double iou=RenderGeometryMetrics.iou(expected.guide().groundPolygon(),observed.polygon());if(Double.isNaN(iou))iou=0;
            costs[i][j]=error*.7+(1-iou)*.3+(compatibleCategory(expected.type(),observed.category())?0:.08);
        }
        int[] assignment=RenderGeometryMetrics.assign(costs);ArrayNode items=result.putArray("items");boolean frameMismatch=!composition.path("cameraMatchesGuide").asBoolean();
        for(int i=0;i<expectedCount;i++) {
            var expected=spatial.furniture().get(i);ObjectNode item=items.addObject();item.put("referenceId",expected.referenceId()).put("productReferenceId",expected.productReferenceId());
            putPoint(item.putArray("expectedGroundCenter1000"),expected.guide().groundCenter());int assigned=i<assignment.length?assignment[i]:-1;
            if(assigned<0||observedCount==0) { item.put("status","FAIL").put("reason","未识别到与该位置匹配的真实家具");overall.add("FAIL");continue; }
            Observation observed=observations.get(assigned);double error=RenderGeometryMetrics.centerError(expected.guide().groundCenter(),observed.center(),diagonal),iou=RenderGeometryMetrics.iou(expected.guide().groundPolygon(),observed.polygon());
            item.put("detectionId",observed.id()).put("centerErrorRatio",error).put("confidence",observed.confidence());putPoint(item.putArray("observedGroundCenter1000"),observed.center());if(!Double.isNaN(iou))item.put("groundPolygonIou",iou);
            String itemStatus;
            if(frameMismatch)itemStatus="UNCERTAIN";else if(observed.confidence()<properties.getGeometryAudit().getMinDetectionConfidence()||!"VISIBLE".equals(observed.visibility()))itemStatus="UNCERTAIN";
            else if(error>properties.getGeometryAudit().getCenterFailRatio()||!Double.isNaN(iou)&&iou<properties.getGeometryAudit().getIouFail())itemStatus="FAIL";
            else if(error>properties.getGeometryAudit().getCenterPassRatio()||Double.isNaN(iou)||iou<properties.getGeometryAudit().getIouPass())itemStatus="REVIEW";else itemStatus="PASS";
            item.put("status",itemStatus).put("reason",itemReason(itemStatus,frameMismatch,observed));overall.add(itemStatus);
        }
        ObjectNode count=result.putObject("furnitureCount");count.put("expected",expectedCount).put("observed",observedCount).put("status",expectedCount==observedCount?"PASS":"FAIL");overall.add(count.path("status").asText());
        result.put("status",overall.contains("FAIL")?"FAIL":overall.contains("REVIEW")||overall.contains("UNCERTAIN")?"UNCERTAIN":"PASS");
        result.put("model",raw.path("model").asText(""));if(raw.hasNonNull("providerRequestId"))result.put("providerRequestId",raw.path("providerRequestId").asText());return result;
    }

    public ObjectNode unavailable() { ObjectNode result=JsonNodeFactory.instance.objectNode();result.put("version",VERSION).put("status","UNAVAILABLE").put("reason","位置几何自动检查不可用，请人工核对");return result; }
    private String itemReason(String status,boolean frameMismatch,Observation observed) {
        if(frameMismatch)return"相机参考系已变化，逐件偏移仅供人工复核";if(!"VISIBLE".equals(observed.visibility()))return"家具被遮挡或无法清晰识别";return switch(status) { case"PASS"->"位置与占地在实验阈值内";case"FAIL"->"中心偏移或占地偏差超过实验阈值";case"REVIEW"->"位置或占地需要人工复核";default->"识别置信度不足，不能判定通过";};
    }
    private static String category(String type) {
        if(type.contains("椅"))return"CHAIR";if(type.contains("沙发"))return"SOFA";if(type.contains("会议桌")||type.contains("茶几"))return"TABLE";if(type.contains("桌")||type.contains("工位"))return"DESK";if(type.contains("柜"))return"STORAGE";return"OTHER";
    }
    private static boolean compatibleCategory(String type,String observed) {
        String expected=category(type);if(expected.equals(observed)||"OTHER".equals(observed))return true;
        return Set.of("TABLE","DESK").contains(expected)&&Set.of("TABLE","DESK").contains(observed);
    }
    private static List<RenderSpatialControl.Point1000> points(JsonNode input) {
        if(!input.isArray())return List.of();List<RenderSpatialControl.Point1000> result=new ArrayList<>();for(JsonNode value:input) { var point=point(value);if(point==null)return List.of();result.add(point); }return result;
    }
    private static RenderSpatialControl.Point1000 point(JsonNode input) {
        if(!input.isArray()||input.size()!=2||!input.get(0).isNumber()||!input.get(1).isNumber())return null;int x=input.get(0).asInt(),y=input.get(1).asInt();return x<0||x>1000||y<0||y>1000?null:new RenderSpatialControl.Point1000(x,y);
    }
    private static RenderSpatialControl.Point1000 centroid(List<RenderSpatialControl.Point1000> points) {
        return new RenderSpatialControl.Point1000((int)Math.round(points.stream().mapToInt(RenderSpatialControl.Point1000::x).average().orElse(0)),(int)Math.round(points.stream().mapToInt(RenderSpatialControl.Point1000::y).average().orElse(0)));
    }
    private static void putPoint(ArrayNode target,RenderSpatialControl.Point1000 point) { target.add(point.x()).add(point.y()); }
    private static String status(String value) { return Set.of("PASS","FAIL","UNCERTAIN").contains(value)?value:"UNCERTAIN"; }
    private static String reason(String value) { return value==null?"":value.replaceAll("[\\p{Cntrl}\\r\\n]+"," ").trim().substring(0,Math.min(500,value.trim().length())); }
}
