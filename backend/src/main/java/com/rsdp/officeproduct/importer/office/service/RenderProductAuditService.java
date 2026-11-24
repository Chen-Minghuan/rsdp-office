package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.service.QwenVisionClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** Non-authoritative visual QA for obvious layout/product mismatches; never edits design geometry. */
@Service
@RequiredArgsConstructor
public class RenderProductAuditService {
    private final QwenVisionClient vision;
    private static final Set<String> STATUSES=Set.of("PASS","FAIL","UNCERTAIN");

    public ObjectNode audit(byte[] result,byte[] guide,List<byte[]> productBoards,
                            RenderFurnitureBindings.Prepared bindings,ArrayNode products) {
        return audit(result,guide,productBoards,bindings,products,null);
    }

    public ObjectNode audit(byte[] result,byte[] guide,List<byte[]> productBoards,
                            RenderFurnitureBindings.Prepared bindings,ArrayNode products,JsonNode layoutFacts) {
        List<byte[]> images=new ArrayList<>();images.add(result);images.add(guide);images.addAll(productBoards);
        String prompt="""
            你是办公空间效果图核对员。图1是生成结果，图2是带F编号的45度结构参考，后续图片是带P编号的商品参考板，商品组顺序与商品绑定中的P编号一致。
            composition的所有布尔字段只描述图1的实际像素。图2及后续图片作为输入参考资料存在，绝不能因此令图1的containsReferenceBoard、containsPlan或containsCollage为true；不得把后续参考图的内容写成了图1的内容。
            只检查明显错误，不要求像素级一致，不因材质、光影、合理装饰或细节差异判失败。
            核对家具总数、主要类型和相对方位；对绑定商品的F家具，核对是否明显使用了对应P商品，包括主要轮廓、柜体位置、桌腿/挡板/扶手和颜色。
            商品条目中的visualProfile是生图前经过程序校验的外观档案，只用于商品审核，不能覆盖布局事实。
            F/P编号是参考关系，成品不应保留编号，但编号残留只记录，不据此判定商品错误。
            看不清或被遮挡时使用UNCERTAIN，不得猜测。仅返回JSON对象：
            {"furnitureCount":{"expected":%d,"observed":0,"status":"PASS|FAIL|UNCERTAIN"},"layout":{"status":"PASS|FAIL|UNCERTAIN","reason":""},"architecture":{"status":"PASS|FAIL|UNCERTAIN","reason":""},"imageQuality":{"status":"PASS|FAIL|UNCERTAIN","reason":""},"composition":{"singleScene":true,"containsReferenceBoard":false,"containsPlan":false,"containsLabels":false,"containsCollage":false,"status":"PASS|FAIL|UNCERTAIN","reason":""},"items":[{"furnitureReferenceId":"F1","productReferenceId":"P1","status":"PASS|FAIL|UNCERTAIN","reason":""}]}
            家具绑定：%s
            商品绑定及商品视觉档案：%s
            建筑事实（仅这些人工标注门窗是既有事实；高度为示意）：%s
            architecture核对已标注门窗是否缺失或明显换墙，并检查明显文字、编号、尺寸线、规格表、拼贴、家具断裂或悬空；imageQuality只记录画面问题。
            composition必须单独检查：只要成品混入商品参考板、平面图、F/P编号、上下分栏或拼贴，composition.status必须为FAIL，不能因家具数量正确而给PASS。
            这里的“成品”始终只指图1，不包括图2和后续商品参考板。
            """.formatted(bindings.items().size(),bindings.manifest().toString(),auditProducts(products).toString(),layoutFacts==null?"{}":layoutFacts.toString());
        JsonNode raw=vision.auditRender(prompt,images);
        return normalize(raw,bindings,layoutFacts);
    }

    private static ArrayNode auditProducts(ArrayNode products) {
        ArrayNode result=JsonNodeFactory.instance.arrayNode();
        for(JsonNode source:products) {
            ObjectNode item=result.addObject();source.properties().forEach(field->{
                if(!"candidateImages".equals(field.getKey()) && !"visualProfile".equals(field.getKey())) item.set(field.getKey(),field.getValue().deepCopy());
            });
            JsonNode profile=source.path("visualProfile");if(!profile.isObject()) continue;
            ObjectNode compact=item.putObject("visualProfile");
            for(String field:List.of("appearancePrompt","assemblyConstraints","mustPreserve")) compact.set(field,profile.path(field).deepCopy());
        }
        return result;
    }
    public ObjectNode unavailable() {
        ObjectNode result=JsonNodeFactory.instance.objectNode();result.put("status","UNAVAILABLE").put("reason","自动检查不可用，请人工核对");return result;
    }

    ObjectNode normalize(JsonNode raw,RenderFurnitureBindings.Prepared bindings) { return normalize(raw,bindings,null); }
    ObjectNode normalize(JsonNode raw,RenderFurnitureBindings.Prepared bindings,JsonNode layoutFacts) {
        if(raw==null || !raw.isObject()) throw new BusinessException("效果图检查没有返回有效对象");
        ObjectNode result=JsonNodeFactory.instance.objectNode();
        ObjectNode count=result.putObject("furnitureCount");JsonNode rawCount=raw.path("furnitureCount");
        count.put("expected",bindings.items().size());
        if(rawCount.path("observed").isInt() && rawCount.path("observed").asInt()>=0 && rawCount.path("observed").asInt()<=100) count.put("observed",rawCount.path("observed").asInt());
        String countStatus=status(rawCount.path("status").asText());count.put("status",countStatus);
        ObjectNode layout=result.putObject("layout");String layoutStatus=status(raw.path("layout").path("status").asText());
        layout.put("status",layoutStatus).put("reason",reason(raw.path("layout").path("reason").asText("")));
        List<String> statuses=new ArrayList<>(List.of(countStatus,layoutStatus));
        if(raw.has("composition")) {
            JsonNode source=raw.path("composition");boolean issue=!source.path("singleScene").asBoolean(true)||source.path("containsReferenceBoard").asBoolean()||source.path("containsPlan").asBoolean()||source.path("containsLabels").asBoolean()||source.path("containsCollage").asBoolean();
            ObjectNode composition=result.putObject("composition");for(String field:List.of("singleScene","containsReferenceBoard","containsPlan","containsLabels","containsCollage"))composition.put(field,source.path(field).asBoolean(!List.of("containsReferenceBoard","containsPlan","containsLabels","containsCollage").contains(field)));
            String compositionStatus=issue?"FAIL":status(source.path("status").asText());composition.put("status",compositionStatus).put("reason",reason(source.path("reason").asText("")));statuses.add(compositionStatus);
        }
        if(layoutFacts!=null) {
            String architectureStatus=status(raw.path("architecture").path("status").asText());
            result.putObject("architecture").put("status",architectureStatus).put("reason",reason(raw.path("architecture").path("reason").asText("")));
            statuses.add(architectureStatus);
        }
        if(raw.has("imageQuality")) {
            String qualityStatus=status(raw.path("imageQuality").path("status").asText());
            result.putObject("imageQuality").put("status",qualityStatus).put("reason",reason(raw.path("imageQuality").path("reason").asText("")));
            statuses.add(qualityStatus);
        }
        Map<String,JsonNode> returned=new HashMap<>();for(JsonNode item:raw.path("items")) returned.put(item.path("furnitureReferenceId").asText(),item);
        ArrayNode items=result.putArray("items");
        for(var binding:bindings.items()) {
            if(binding.productId().isBlank()) continue;
            JsonNode item=returned.get(binding.referenceId());ObjectNode checked=items.addObject();
            checked.put("furnitureReferenceId",binding.referenceId()).put("productReferenceId",binding.productReferenceId());
            String itemStatus=item==null?"UNCERTAIN":status(item.path("status").asText());
            checked.put("status",itemStatus).put("reason",item==null?"模型没有返回该家具的检查结果":reason(item.path("reason").asText("")));
            statuses.add(itemStatus);
        }
        String overall=statuses.contains("FAIL")?"FAIL":statuses.contains("UNCERTAIN")?"UNCERTAIN":"PASS";
        result.put("status",overall);result.put("model",raw.path("model").asText(""));
        if(raw.hasNonNull("providerRequestId")) result.put("providerRequestId",raw.path("providerRequestId").asText());
        return result;
    }
    private static String status(String value) { return STATUSES.contains(value)?value:"UNCERTAIN"; }
    private static String reason(String value) { String clean=value.replaceAll("[\\p{Cntrl}\\r\\n]+"," ").trim();return clean.substring(0,Math.min(500,clean.length())); }
}
