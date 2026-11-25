package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.RenderProperties;
import com.rsdp.officeproduct.importer.config.VisionProperties;
import com.rsdp.officeproduct.importer.service.QwenVisionClient;
import com.rsdp.officeproduct.importer.service.VisionImagePreparer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Pre-render target/product-image understanding. It cannot define layout or F/P bindings. */
@Service
@RequiredArgsConstructor
public class ProductVisualUnderstandingService {
    public static final String SCHEMA_VERSION="product-visual-profile-v2";
    public static final String PROMPT_VERSION="reference-understanding-v2-target-audit";
    private static final Set<String> VIEWS=Set.of("FRONT","BACK","LEFT","RIGHT","FRONT_3Q","BACK_3Q","DETAIL","UNKNOWN");
    private static final Set<String> COMPOSITIONS=Set.of("SINGLE_PRODUCT","PRODUCT_SET","MULTIPLE_PRODUCTS","MULTI_VIEW","UNKNOWN");
    private static final Set<String> TARGET_STATUSES=Set.of("IDENTIFIED","AMBIGUOUS","NOT_FOUND","UNKNOWN");
    private static final Set<String> OBJECT_ROLES=Set.of("TARGET","RELATED_PRODUCT","ACCESSORY","BACKGROUND","UNKNOWN");
    private static final Set<String> FORBIDDEN=Set.of("furniture","furnitureCount","layout","coordinates","placements","openings","bindings","newVariantId");
    private static final String SYSTEM_PROMPT="""
        你是一名具有资深办公家具设计经验的商品参考图审核员。你的职责不是设计室内空间，而是为生图流程审核商品参考图片：先识别图片构成和目标主体，再提取目标商品的可见外观事实。
        逐张检查家具、配饰和背景，不得预设输入只有一款商品。桌椅套组、办公位组合、沙发与茶几组合不得当作一件商品；茶具、书籍和植物属于配饰。
        imageComposition只使用SINGLE_PRODUCT/PRODUCT_SET/MULTIPLE_PRODUCTS/MULTI_VIEW/UNKNOWN。
        根据商品记录和可见证据确定TARGET，并区分RELATED_PRODUCT、ACCESSORY、BACKGROUND。目标不明确时使用AMBIGUOUS或UNKNOWN，不得自行猜测。
        wholeProductVisible只表示目标商品本身是否完整；suitableAsPrimary只表示图片能否提供目标商品外观证据，不表示原图可以不经处理直接生图。
        原图包含非目标商品时，imageComposition必须为PRODUCT_SET或MULTIPLE_PRODUCTS，并同时返回containsNonTargetProducts=true、requiresSubjectIsolation=true、originalImageSuitableForDirectGeneration=false。
        只描述目标商品的轮廓、部件、装配、材质、颜色和可见异常，不把配套商品、配饰或背景写入商品档案，不补充不可见结构。
        不设计空间、不推荐搭配，不决定家具数量、坐标、尺寸、门窗、摆放位置或F/P绑定。商品照片中的物品数量不得转化为空间家具数量。
        不确定时使用UNKNOWN、warnings和uncertainties，不得猜测。只返回符合用户给定结构的JSON对象。
        """;

    private final QwenVisionClient vision;
    private final ProductVisualProfileCache cache;
    private final VisionProperties visionProperties;
    private final RenderProperties renderProperties;
    private final ObjectMapper json;

    public record SourceImage(String imageId,String imageUrl,String imageSha256,byte[] png) {
        public SourceImage {
            if(imageId==null || imageId.isBlank() || imageSha256==null || !imageSha256.matches("[a-f0-9]{64}") || png==null || png.length<100)
                throw new BusinessException("商品理解输入图片无效");
        }
    }
    public record Request(String productId,String rspuId,String variantId,String productName,List<SourceImage> images) {
        public Request { images=List.copyOf(images); }
    }
    public record ItemResult(String status,ObjectNode profile,List<String> warnings) {
        public boolean usable() { return profile!=null && ("GENERATED".equals(status) || "CACHE_HIT".equals(status)); }
    }
    public record Batch(String status,String model,int cacheHits,int generatedCount,Map<String,ItemResult> items) {
        public static Batch empty() { return new Batch("NOT_REQUIRED","",0,0,Map.of()); }
        public static Batch disabled(List<Request> requests) {
            Map<String,ItemResult> items=new LinkedHashMap<>();
            requests.forEach(request->items.put(request.productId(),new ItemResult("DISABLED",null,List.of("商品理解智能体未启用，使用静态规则"))));
            return new Batch("DISABLED","",0,0,items);
        }
        public ItemResult forProduct(String productId) { return items.get(productId); }
        public ObjectNode manifest(int maxNewProducts) {
            ObjectNode result=JsonNodeFactory.instance.objectNode();
            result.put("status",status).put("agentModel",model).put("agentPromptVersion",PROMPT_VERSION).put("schemaVersion",SCHEMA_VERSION);
            result.put("cacheHits",cacheHits).put("generatedCount",generatedCount).put("maxNewProductsPerRender",maxNewProducts);
            ArrayNode entries=result.putArray("items");
            items.forEach((productId,item)->{
                ObjectNode entry=entries.addObject().put("productId",productId).put("status",item.status());
                if(item.profile()!=null) entry.put("profileCacheKey",item.profile().path("cacheKey").asText());
                ArrayNode warnings=entry.putArray("warnings");item.warnings().forEach(warnings::add);
            });
            return result;
        }
    }

    public int maxNewProductsPerRender() { return Math.max(0,renderProperties.getProductAgent().getMaxNewProductsPerRender()); }

    public Batch understandAll(List<Request> requests) {
        if(requests.isEmpty()) return Batch.empty();
        String model=visionProperties.getModel()==null?"":visionProperties.getModel();
        if(!renderProperties.getProductAgent().isEnabled()) {
            Map<String,ItemResult> items=new LinkedHashMap<>(Batch.disabled(requests).items());
            return new Batch("DISABLED",model,0,0,items);
        }
        int maxNew=Math.max(0,renderProperties.getProductAgent().getMaxNewProductsPerRender());
        Map<String,ItemResult> items=new LinkedHashMap<>();Map<String,String> keys=new LinkedHashMap<>();int cacheHits=0,generated=0;
        for(Request request:requests) {
            String key=cacheKey(request,model);keys.put(request.productId(),key);
            var cached=cache.find(key);
            if(cached.isPresent() && validCached(cached.get(),request,key)) {
                items.put(request.productId(),new ItemResult("CACHE_HIT",cached.get(),List.of()));cacheHits++;
            }
        }
        for(Request request:requests) {
            if(items.containsKey(request.productId())) continue;
            if(generated>=maxNew) {
                items.put(request.productId(),new ItemResult("SKIPPED",null,List.of("超过本次首次商品理解数量限制，使用静态规则")));continue;
            }
            generated++;String key=keys.get(request.productId());
            try {
                JsonNode raw=vision.understandProduct(SYSTEM_PROMPT,prompt(request),request.images().stream().map(SourceImage::png).toList());
                ObjectNode profile=normalize(raw,request,key);List<String> warnings=new ArrayList<>();
                try { cache.save(key,profile); } catch(BusinessException failure) { warnings.add("商品视觉档案未能写入缓存，本次仍可使用"); }
                items.put(request.productId(),new ItemResult("GENERATED",profile,warnings));
            } catch(ProfileInvalid failure) {
                items.put(request.productId(),new ItemResult("INVALID_RESPONSE",null,List.of("商品理解结果未通过结构或目标一致性校验，已阻止本次生图，请核对商品目标或上传单品图")));
            } catch(RuntimeException failure) {
                items.put(request.productId(),new ItemResult("UNAVAILABLE",null,List.of("商品理解暂不可用，使用静态规则")));
            }
        }
        long usable=items.values().stream().filter(ItemResult::usable).count();
        String status=usable==requests.size()?"SUCCEEDED":usable>0?"PARTIAL":"UNAVAILABLE";
        return new Batch(status,model,cacheHits,(int)items.values().stream().filter(item->"GENERATED".equals(item.status())).count(),items);
    }

    private String cacheKey(Request request,String model) {
        ObjectNode key=json.createObjectNode();
        key.put("productId",safe(request.productId())).put("variantId",safe(request.variantId()))
            .put("agentModel",safe(model)).put("agentPromptVersion",PROMPT_VERSION).put("schemaVersion",SCHEMA_VERSION);
        ArrayNode hashes=key.putArray("sourceImageHashes");
        request.images().stream().map(SourceImage::imageSha256).sorted().forEach(hashes::add);
        try { return VisionImagePreparer.sha256(json.writeValueAsString(key).getBytes(StandardCharsets.UTF_8)); }
        catch(Exception e) { throw new BusinessException("商品理解缓存键生成失败"); }
    }

    private boolean validCached(ObjectNode profile,Request request,String key) {
        if(!SCHEMA_VERSION.equals(profile.path("schemaVersion").asText()) || !PROMPT_VERSION.equals(profile.path("agentPromptVersion").asText())
            || !safe(request.productId()).equals(profile.path("productId").asText()) || !safe(request.variantId()).equals(profile.path("variantId").asText())) return false;
        List<String> expected=request.images().stream().map(SourceImage::imageSha256).sorted().toList();
        List<String> actual=new ArrayList<>();profile.path("sourceImageHashes").forEach(item->actual.add(item.asText()));actual.sort(Comparator.naturalOrder());
        return key.equals(profile.path("cacheKey").asText()) && expected.equals(actual) && FORBIDDEN.stream().noneMatch(profile::has);
    }

    private String prompt(Request request) {
        StringBuilder imageIds=new StringBuilder();request.images().forEach(image->imageIds.append(image.imageId()).append(' '));
        return """
            商品记录：productId=%s，rspuId=%s，variantId=%s，商品名=%s。图片按以下ID顺序输入：%s。
            逐张判断FRONT/BACK/LEFT/RIGHT/FRONT_3Q/BACK_3Q/DETAIL/UNKNOWN，识别图片构成、目标主体、整体轮廓、部件、装配关系、材质颜色、完整度和异常。
            bbox以图片左上角为原点，x向右、y向下，归一化到0～1000，格式[left,top,right,bottom]；无法可靠定位目标时返回空数组，不得猜测坐标。
            detectedObjects逐项给出imageId、category、role、bbox和confidence。图片排列不表示方向，不要把不同视角结构叠加到同一可见面。
            warnings代码只使用VARIANT_MIX/WRONG_PRODUCT/MULTIPLE_PRODUCTS/UNREADABLE/BLURRY/OCCLUDED/TEXT_OVERLOAD/OTHER_WARNING。
            只返回一个JSON对象，不要Markdown，不要reasoning_content：
            {"schemaVersion":"product-visual-profile-v2","productId":"%s","rspuId":"%s","variantId":"%s",
            "views":[{"imageId":"输入图片ID","view":"FRONT_3Q","imageComposition":"SINGLE_PRODUCT","target":{"status":"IDENTIFIED","category":"","description":"","bbox":[],"confidence":0.0},"containsNonTargetProducts":false,"requiresSubjectIsolation":false,"originalImageSuitableForDirectGeneration":true,"confidence":0.0,"wholeProductVisible":true,"suitableAsPrimary":true,"warnings":[]}],
            "detectedObjects":[{"imageId":"输入图片ID","category":"","role":"TARGET","bbox":[0,0,1000,1000],"confidence":0.0}],
            "overallShape":{"description":"","evidenceImageIds":[],"confidence":0.0},
            "components":[{"name":"","description":"","mustPreserve":true,"evidenceImageIds":[],"confidence":0.0}],
            "assemblyConstraints":[],"mustPreserve":[],"materialsAndColors":[{"description":"","evidenceImageIds":[]}],
            "appearancePrompt":"","warnings":[],"uncertainties":[]}
            所有evidenceImageIds必须来自输入图片。appearancePrompt只写目标商品可见外观与装配约束，不写空间布局，不超过300字。
            """.formatted(safe(request.productId()),safe(request.rspuId()),safe(request.variantId()),safe(request.productName()),imageIds.toString().trim(),
                safe(request.productId()),safe(request.rspuId()),safe(request.variantId()));
    }

    ObjectNode normalize(JsonNode raw,Request request,String key) throws ProfileInvalid {
        if(raw==null || !raw.isObject() || FORBIDDEN.stream().anyMatch(raw::has)) throw new ProfileInvalid();
        if(!safe(request.productId()).equals(raw.path("productId").asText()) || !safe(request.rspuId()).equals(raw.path("rspuId").asText())
            || !safe(request.variantId()).equals(raw.path("variantId").asText())) throw new ProfileInvalid();
        Map<String,SourceImage> sources=new LinkedHashMap<>();request.images().forEach(image->sources.put(image.imageId(),image));
        Map<String,JsonNode> returned=new LinkedHashMap<>();
        if(!raw.path("views").isArray()) throw new ProfileInvalid();
        for(JsonNode view:raw.path("views")) {
            String imageId=view.path("imageId").asText();
            if(!sources.containsKey(imageId) || returned.put(imageId,view)!=null) throw new ProfileInvalid();
        }
        ObjectNode result=json.createObjectNode();result.put("schemaVersion",SCHEMA_VERSION).put("productId",safe(request.productId()))
            .put("rspuId",safe(request.rspuId())).put("variantId",safe(request.variantId()));
        ArrayNode hashes=result.putArray("sourceImageHashes");sources.values().stream().map(SourceImage::imageSha256).sorted().forEach(hashes::add);
        ArrayNode views=result.putArray("views");
        for(String imageId:sources.keySet()) {
            JsonNode input=returned.get(imageId);ObjectNode view=views.addObject().put("imageId",imageId).put("imageSha256",sources.get(imageId).imageSha256());
            if(input==null) {
                view.put("view","UNKNOWN").put("imageComposition","UNKNOWN").set("target",unknownTarget());
                view.put("containsNonTargetProducts",false).put("requiresSubjectIsolation",false).put("originalImageSuitableForDirectGeneration",false);
                view.put("confidence",0).put("wholeProductVisible",false).put("suitableAsPrimary",false);
                view.putArray("warnings").add("UNREADABLE");continue;
            }
            String viewName=input.path("view").asText(),composition=input.path("imageComposition").asText();
            if(!VIEWS.contains(viewName) || !COMPOSITIONS.contains(composition)) throw new ProfileInvalid();
            view.put("view",viewName).put("imageComposition",composition);view.set("target",target(input.path("target")));
            boolean contains=requiredBoolean(input,"containsNonTargetProducts"),requires=requiredBoolean(input,"requiresSubjectIsolation"),direct=requiredBoolean(input,"originalImageSuitableForDirectGeneration");
            view.put("containsNonTargetProducts",contains).put("requiresSubjectIsolation",requires).put("originalImageSuitableForDirectGeneration",direct);
            view.set("confidence",number(input.path("confidence")));
            view.put("wholeProductVisible",input.path("wholeProductVisible").asBoolean(false)).put("suitableAsPrimary",input.path("suitableAsPrimary").asBoolean(false));
            ArrayNode warnings=view.putArray("warnings");limitedCodes(input.path("warnings"),warnings);
            boolean multi="PRODUCT_SET".equals(composition) || "MULTIPLE_PRODUCTS".equals(composition);
            if(multi!=contains || (contains && (!requires || direct || !hasCode(warnings,"MULTIPLE_PRODUCTS")))) throw new ProfileInvalid();
        }
        result.set("detectedObjects",detectedObjects(raw.path("detectedObjects"),sources.keySet()));
        result.set("overallShape",shape(raw.path("overallShape"),sources.keySet()));
        ArrayNode components=result.putArray("components");
        if(raw.path("components").size()>24) throw new ProfileInvalid();
        for(JsonNode input:raw.path("components")) {
            ObjectNode component=components.addObject().put("name",text(input.path("name"),80)).put("description",text(input.path("description"),400))
                .put("mustPreserve",input.path("mustPreserve").asBoolean(true));
            component.set("evidenceImageIds",evidence(input.path("evidenceImageIds"),sources.keySet(),true));
            component.set("confidence",number(input.path("confidence")));
        }
        strings(raw.path("assemblyConstraints"),result.putArray("assemblyConstraints"),24,300);
        strings(raw.path("mustPreserve"),result.putArray("mustPreserve"),24,300);
        ArrayNode materials=result.putArray("materialsAndColors");
        if(raw.path("materialsAndColors").size()>16) throw new ProfileInvalid();
        for(JsonNode input:raw.path("materialsAndColors")) {
            ObjectNode material=materials.addObject().put("description",text(input.path("description"),300));
            material.set("evidenceImageIds",evidence(input.path("evidenceImageIds"),sources.keySet(),true));
        }
        result.put("appearancePrompt",text(raw.path("appearancePrompt"),600));
        strings(raw.path("warnings"),result.putArray("warnings"),24,300);
        strings(raw.path("uncertainties"),result.putArray("uncertainties"),24,300);
        result.put("agentModel",text(raw.path("model"),160)).put("agentPromptVersion",PROMPT_VERSION)
            .put("cacheKey",key).put("understoodAt",Instant.now().toString());
        if(raw.hasNonNull("providerRequestId")) result.put("providerRequestId",text(raw.path("providerRequestId"),160));
        return result;
    }

    private ObjectNode target(JsonNode input) throws ProfileInvalid {
        if(!input.isObject() || !TARGET_STATUSES.contains(input.path("status").asText())) throw new ProfileInvalid();
        ObjectNode result=json.createObjectNode().put("status",input.path("status").asText())
            .put("category",text(input.path("category"),80)).put("description",text(input.path("description"),400));
        result.set("bbox",bbox(input.path("bbox"),true));result.set("confidence",number(input.path("confidence")));return result;
    }
    private ObjectNode unknownTarget() {
        ObjectNode result=json.createObjectNode().put("status","UNKNOWN").put("category","").put("description","").put("confidence",0);
        result.putArray("bbox");return result;
    }
    private ArrayNode detectedObjects(JsonNode input,Set<String> ids) throws ProfileInvalid {
        if(!input.isArray() || input.size()>32) throw new ProfileInvalid();ArrayNode result=JsonNodeFactory.instance.arrayNode();
        for(JsonNode object:input) {
            String imageId=object.path("imageId").asText(),role=object.path("role").asText();
            if(!ids.contains(imageId) || !OBJECT_ROLES.contains(role)) throw new ProfileInvalid();
            ObjectNode entry=result.addObject().put("imageId",imageId).put("category",text(object.path("category"),80)).put("role",role);
            entry.set("bbox",bbox(object.path("bbox"),false));entry.set("confidence",number(object.path("confidence")));
        }
        return result;
    }
    private ArrayNode bbox(JsonNode input,boolean allowEmpty) throws ProfileInvalid {
        if(!input.isArray() || (input.isEmpty() && allowEmpty)) {
            if(input!=null && input.isArray() && input.isEmpty() && allowEmpty) return JsonNodeFactory.instance.arrayNode();
            throw new ProfileInvalid();
        }
        if(input.size()!=4) throw new ProfileInvalid();double[] v=new double[4];ArrayNode result=JsonNodeFactory.instance.arrayNode();
        for(int i=0;i<4;i++) {
            JsonNode n=input.get(i);if(!n.isNumber() || !Double.isFinite(n.asDouble()) || n.asDouble()<0 || n.asDouble()>1000) throw new ProfileInvalid();
            v[i]=n.asDouble();result.add(v[i]);
        }
        if(v[0]>=v[2] || v[1]>=v[3]) throw new ProfileInvalid();return result;
    }
    private boolean requiredBoolean(JsonNode input,String field) throws ProfileInvalid {
        if(!input.path(field).isBoolean()) throw new ProfileInvalid();return input.path(field).asBoolean();
    }
    private boolean hasCode(ArrayNode warnings,String code) { for(JsonNode warning:warnings) if(code.equals(warning.asText())) return true;return false; }
    private ObjectNode shape(JsonNode input,Set<String> ids) throws ProfileInvalid {
        ObjectNode result=json.createObjectNode().put("description",text(input.path("description"),500));
        result.set("evidenceImageIds",evidence(input.path("evidenceImageIds"),ids,!result.path("description").asText().isBlank()));
        result.set("confidence",input.isMissingNode()||input.isNull()?JsonNodeFactory.instance.numberNode(0):number(input.path("confidence")));return result;
    }
    private ArrayNode evidence(JsonNode input,Set<String> ids,boolean required) throws ProfileInvalid {
        ArrayNode result=JsonNodeFactory.instance.arrayNode();LinkedHashSet<String> unique=new LinkedHashSet<>();
        for(JsonNode value:input) { String id=value.asText();if(!ids.contains(id)) throw new ProfileInvalid();unique.add(id); }
        if(required && unique.isEmpty()) throw new ProfileInvalid();unique.forEach(result::add);return result;
    }
    private JsonNode number(JsonNode input) throws ProfileInvalid {
        if(!input.isNumber() || !Double.isFinite(input.asDouble()) || input.asDouble()<0 || input.asDouble()>1) throw new ProfileInvalid();
        return JsonNodeFactory.instance.numberNode(input.asDouble());
    }
    private void strings(JsonNode input,ArrayNode output,int maxCount,int maxLength) throws ProfileInvalid {
        if(input.size()>maxCount) throw new ProfileInvalid();
        for(JsonNode value:input) { if(!value.isTextual()) throw new ProfileInvalid();output.add(text(value,maxLength)); }
    }
    private void limitedCodes(JsonNode input,ArrayNode output) throws ProfileInvalid {
        if(input.size()>12) throw new ProfileInvalid();
        for(JsonNode value:input) {
            String code=text(value,40).toUpperCase().replaceAll("[^A-Z_]","");
            if(code.isBlank()) throw new ProfileInvalid();output.add(code);
        }
    }
    private static String text(JsonNode value,int maxLength) {
        String clean=value==null || !value.isValueNode()?"":value.asText("").replaceAll("[\\p{Cntrl}\\r\\n]+"," ").trim();
        return clean.substring(0,Math.min(maxLength,clean.length()));
    }
    private static String safe(String value) { return value==null?"":value.trim(); }
    static class ProfileInvalid extends Exception { }
}
