package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Compiles the same validated scene facts into a short visual editing task for the controlled B/C/D arms. */
final class RenderVisualTaskPrompt {
    private RenderVisualTaskPrompt() {}
    static String compile(JsonNode scene,String style,String instructions,RenderFurnitureBindings.Prepared bindings,
                          RenderProductReferences.Prepared products,RenderSpatialControl.Prepared spatial,boolean includePlan) {
        StringBuilder text=new StringBuilder("图1决定最终相机、房间结构和立体布局。");
        if(includePlan)text.append("图2决定家具数量和地面排列，后续商品参考板只提供商品外观。\n");
        else text.append("图1同时决定家具数量和地面排列，后续商品参考板只提供商品外观。\n");
        text.append("商品参考图不提供家具数量、摆放位置或房间设计。将当前白模忠实转换为照片级写实的3D室内效果图，应用指定商品外观，但不重新设计房间。\n");
        text.append("场景严格保持").append(spatial.furniture().size()).append("件家具，不得新增、删除、复制或重新排列。\n");
        appendProducts(text,bindings);
        appendLayout(text,spatial);
        text.append("保持所有家具原来的位置、朝向、尺寸比例、排列顺序和间距，保持房间轮廓、门窗和相机视角不变。\n");
        appendAppearance(text,products);
        text.append("只补充真实材质、自然光照、合理接触阴影和墙地面质感。风格：").append(style.trim()).append("。\n");
        if(instructions!=null&&!instructions.isBlank())text.append("用户补充偏好：").append(instructions.trim()).append("。\n");
        text.append("最终只输出一个完整室内场景，不输出F/P编号、文字、标注线、平面图、商品板、拼贴或分栏。\n");
        return text.toString();
    }
    private static void appendProducts(StringBuilder text,RenderFurnitureBindings.Prepared bindings) {
        Map<String,List<RenderFurnitureBindings.Binding>> groups=new LinkedHashMap<>();
        for(var item:bindings.items())if(!item.productReferenceId().isBlank())groups.computeIfAbsent(item.productReferenceId(),key->new ArrayList<>()).add(item);
        groups.forEach((product,items)->text.append(product).append("外观使用数量为").append(items.size()).append("件，商品类型：")
            .append(items.stream().map(RenderFurnitureBindings.Binding::type).distinct().reduce((a,b)->a+"、"+b).orElse("家具")).append("；商品照片数量不代表家具数量。\n"));
    }
    private static void appendLayout(StringBuilder text,RenderSpatialControl.Prepared spatial) {
        boolean structured=false;
        for(JsonNode fact:spatial.manifest().path("layoutSemantics")) {
            String kind=fact.path("kind").asText();
            if(kind.equals("TWO_ROWS")) { text.append("两侧座椅组成两条彼此相对的直线排，不得改为交错、弧形或散乱布局。\n");structured=true; }
            if(kind.equals("SIDE_ROW")) {
                text.append(fact.path("primaryType").asText("主体家具")).append(fact.path("side").asText("一侧")).append("的")
                    .append(fact.path("count").asInt()).append("把座椅组成直线一排，并与主体长边平行");
                if(fact.path("productRefs").size()==1)text.append("，均使用").append(fact.path("productRefs").get(0).asText()).append("商品外观");
                text.append("。\n");structured=true;
            }
            if(kind.equals("HEAD_SEAT")) {
                text.append(fact.path("primaryType").asText("主体家具")).append(fact.path("direction").asText()).append("端部1把座椅");
                if(!fact.path("productRef").asText().isBlank())text.append("使用").append(fact.path("productRef").asText()).append("商品外观");
                text.append("，保持端部居中，不得挪到侧排或桌角。\n");structured=true;
            }
        }
        if(!structured)text.append(includePlanText(spatial));
    }
    private static String includePlanText(RenderSpatialControl.Prepared spatial) { return "严格保持参考图所示各家具的相对位置、排列连线和实际间距，不重新组织布局。\n"; }
    private static void appendAppearance(StringBuilder text,RenderProductReferences.Prepared products) {
        for(JsonNode product:products.manifest()) {
            String ref=product.path("referenceId").asText();if(ref.isBlank())continue;
            text.append(ref).append("商品外观：");RenderPromptCompiler.appendVisualProfile(text,product.path("visualProfile"));
        }
    }
}