package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.rsdp.officeproduct.importer.common.BusinessException;
import java.awt.geom.Rectangle2D;
import java.util.*;

/** Stable per-render furniture/product references shared by the guide, prompt and audit manifest. */
public final class RenderFurnitureBindings {
    private RenderFurnitureBindings() {}

    public record Binding(String referenceId,String furnitureId,String type,String location,
                          double centerX,double centerY,int rotation,String productId,String productReferenceId) {}

    public record Prepared(List<Binding> items) {
        public Prepared { items=List.copyOf(items); }
        public Binding byFurnitureId(String furnitureId) {
            return items.stream().filter(item->item.furnitureId().equals(furnitureId)).findFirst()
                .orElseThrow(()->new BusinessException("家具定位关系缺失，请重新保存排版后生成"));
        }
        public String prompt() {
            StringBuilder text=new StringBuilder("\n图1家具定位关系：共").append(items.size()).append("件家具，不得增加、删除、合并、替换或移动。\n");
            for(Binding item:items) {
                text.append(item.referenceId()).append("是").append(item.type()).append("，位于").append(item.location())
                    .append("，保持图1中的位置、").append(item.rotation()).append("度朝向和占地比例");
                if(!item.productReferenceId().isBlank()) text.append("，必须使用").append(item.productReferenceId()).append("商品外观");
                else text.append("，未绑定商品，只按该家具类型表达");
                text.append("。\n");
            }
            text.append("F编号和标签引线仅用于识别图1中的对应家具，不是场景实体，不得出现在最终图片中。未绑定商品的家具不得套用其他家具的商品外观。\n");
            return text.toString();
        }
        public ArrayNode manifest() {
            ArrayNode result=JsonNodeFactory.instance.arrayNode();
            for(Binding item:items) {
                var entry=result.addObject().put("referenceId",item.referenceId()).put("furnitureId",item.furnitureId())
                    .put("type",item.type()).put("location",item.location()).put("centerX",item.centerX()).put("centerY",item.centerY())
                    .put("rotation",item.rotation()).put("productId",item.productId()).put("productReferenceId",item.productReferenceId());
            }
            return result;
        }
    }

    public static Prepared from(JsonNode scene) {
        List<JsonNode> furniture=new ArrayList<>();scene.path("furniture").forEach(furniture::add);
        furniture.sort(Comparator.comparing(item->item.path("id").asText()));
        Map<String,String> productReferences=new LinkedHashMap<>();
        Rectangle2D room=scene.path("zone").path("polygon").size()>=3
            ?RenderPreviewScene.roomPath(scene.path("zone").path("polygon")).getBounds2D():new Rectangle2D.Double(0,0,1,1);
        List<Binding> result=new ArrayList<>();
        for(int i=0;i<furniture.size();i++) {
            JsonNode item=furniture.get(i);String productId=item.path("productId").asText(""),productReference="";
            if(!productId.isBlank()) productReference=productReferences.computeIfAbsent(productId,key->"P"+(productReferences.size()+1));
            Rectangle2D footprint=RenderPreviewScene.furnitureShape(item).getBounds2D();
            double centerX=footprint.getCenterX(),centerY=footprint.getCenterY();
            int rotation=((item.path("rotation").asInt()%360)+360)%360;
            result.add(new Binding("F"+(i+1),item.path("id").asText(),type(item),location(room,centerX,centerY),
                centerX,centerY,rotation,productId,productReference));
        }
        return new Prepared(result);
    }

    private static String type(JsonNode item) {
        String value=item.path("label").asText("").trim();
        if(value.isBlank()) value=item.path("moduleSnapshot").path("name").asText("").trim();
        if(value.isBlank()) value=item.path("furnitureTemplateId").asText("办公家具").trim();
        value=value.replaceAll("[\\p{Cntrl}\\r\\n]+"," ");
        return value.substring(0,Math.min(48,value.length()));
    }
    private static String location(Rectangle2D room,double x,double y) {
        double nx=(x-room.getMinX())/Math.max(room.getWidth(),1),ny=(y-room.getMinY())/Math.max(room.getHeight(),1);
        String horizontal=nx<1.0/3?"左侧":nx>2.0/3?"右侧":"中间";
        String vertical=ny<1.0/3?"后侧":ny>2.0/3?"前侧":"中部";
        return "空间"+horizontal+vertical;
    }
}
