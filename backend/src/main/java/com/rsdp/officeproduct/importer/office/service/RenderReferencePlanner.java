package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.rsdp.officeproduct.importer.common.BusinessException;

import java.util.*;

/** Deterministic conservative reference selection; the visual model never chooses or edits layout. */
public final class RenderReferencePlanner {
    private static final double MIN_CONFIDENCE=.65;
    private static final Set<String> EXCLUDED=Set.of("VARIANT_MIX","WRONG_PRODUCT","MULTIPLE_PRODUCTS","UNREADABLE","BLURRY","OCCLUDED","TEXT_OVERLOAD");
    private static final Set<String> MAIN_BLOCKED=Set.of("VARIANT_MIX","WRONG_PRODUCT","UNREADABLE");
    private RenderReferencePlanner() { }

    public record Selection(List<ProductVisualUnderstandingService.SourceImage> images,List<String> warnings) { }

    public static Selection select(List<ProductVisualUnderstandingService.SourceImage> candidates,JsonNode profile,String productName) {
        if(profile==null) return new Selection(List.copyOf(candidates),List.of());
        Map<String,String> aliases=new HashMap<>();
        for(var candidate:candidates) {
            JsonNode matched=view(profile,candidate);
            if(matched!=null) aliases.put(matched.path("imageId").asText(),candidate.imageId());
        }
        JsonNode main=view(profile,candidates.get(0));
        if(main!=null && unresolvedTarget(main))
            throw new BusinessException("商品主图中的目标商品不明确，请上传单品图或人工确认目标："+productName);
        if(main!=null && (warnings(main).stream().anyMatch(MAIN_BLOCKED::contains)
            || warnings(main).contains("MULTIPLE_PRODUCTS") && !canIsolate(main)))
            throw new BusinessException("商品主图可能混入其他商品或无法可靠隔离，请先核对商品图片："+productName);
        List<JsonNode> usable=new ArrayList<>();
        for(var candidate:candidates) {
            JsonNode view=view(profile,candidate);
            if(view==null || unresolvedTarget(view) || !view.path("wholeProductVisible").asBoolean() || !view.path("suitableAsPrimary").asBoolean()
                || view.path("confidence").asDouble()<MIN_CONFIDENCE || hasExcludedWarning(view)) continue;
            usable.add(view);
        }
        if(usable.isEmpty()) return new Selection(List.of(candidates.get(0)),List.of("商品视角无法可靠确认，本次仅使用主图"));
        usable.sort((a,b)->{
            int confidence=Double.compare(b.path("confidence").asDouble(),a.path("confidence").asDouble());
            if(confidence!=0) return confidence;
            return Integer.compare(index(candidates,aliases.getOrDefault(a.path("imageId").asText(),a.path("imageId").asText())),index(candidates,aliases.getOrDefault(b.path("imageId").asText(),b.path("imageId").asText())));
        });
        String primaryId=aliases.getOrDefault(usable.get(0).path("imageId").asText(),usable.get(0).path("imageId").asText());
        LinkedHashSet<String> selected=new LinkedHashSet<>();selected.add(primaryId);
        Set<String> rawEvidence=new LinkedHashSet<>();
        evidence(profile.path("overallShape").path("evidenceImageIds"),rawEvidence);
        for(JsonNode component:profile.path("components")) evidence(component.path("evidenceImageIds"),rawEvidence);
        Set<String> evidence=rawEvidence.stream().map(id->aliases.getOrDefault(id,id)).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for(JsonNode view:usable) {
            String imageId=aliases.getOrDefault(view.path("imageId").asText(),view.path("imageId").asText());
            if(selected.size()>=2) break;
            if(!primaryId.equals(imageId) && evidence.contains(imageId) && !view.path("view").asText().equals(usable.get(0).path("view").asText())) selected.add(imageId);
        }
        List<ProductVisualUnderstandingService.SourceImage> images=new ArrayList<>();
        for(String imageId:selected) images.add(candidates.stream().filter(item->item.imageId().equals(imageId)).findFirst().orElseThrow());
        List<String> warnings=new ArrayList<>();
        if(images.size()==1) warnings.add("按商品理解结果使用一个整体参考视角，减少正反面融合");
        else warnings.add("按商品结构证据补充一个整体参考视角");
        return new Selection(images,warnings);
    }

    private static boolean hasExcludedWarning(JsonNode view) {
        for(String warning:warnings(view)) {
            if(!EXCLUDED.contains(warning)) continue;
            if(!"MULTIPLE_PRODUCTS".equals(warning) || !canIsolate(view)) return true;
        }
        return false;
    }
    private static boolean unresolvedTarget(JsonNode view) {
        JsonNode target=view.path("target");
        return target.has("status") && !"IDENTIFIED".equals(target.path("status").asText());
    }

    private static boolean canIsolate(JsonNode view) {
        JsonNode target=view.path("target");
        return view.path("requiresSubjectIsolation").asBoolean(false)
            && "IDENTIFIED".equals(target.path("status").asText()) && target.path("confidence").asDouble()>=.8
            && target.path("bbox").size()==4;
    }

    private static JsonNode view(JsonNode profile,ProductVisualUnderstandingService.SourceImage candidate) {
        for(JsonNode view:profile.path("views")) if(candidate.imageId().equals(view.path("imageId").asText())) return view;
        for(JsonNode view:profile.path("views")) if(candidate.imageSha256().equals(view.path("imageSha256").asText())) return view;
        return null;
    }
    private static List<String> warnings(JsonNode view) {
        List<String> result=new ArrayList<>();view.path("warnings").forEach(item->result.add(item.asText()));return result;
    }
    private static int index(List<ProductVisualUnderstandingService.SourceImage> candidates,String imageId) {
        for(int i=0;i<candidates.size();i++) if(candidates.get(i).imageId().equals(imageId)) return i;
        return Integer.MAX_VALUE;
    }
    private static void evidence(JsonNode input,Set<String> output) { input.forEach(item->output.add(item.asText())); }
}
