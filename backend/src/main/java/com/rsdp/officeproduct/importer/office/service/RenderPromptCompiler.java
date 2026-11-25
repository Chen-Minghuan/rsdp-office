package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;

/** The only component allowed to compile visual-profile text into the final provider prompt. */
public final class RenderPromptCompiler {
    private RenderPromptCompiler() { }

    public static String compile(JsonNode scene,String style,String instructions,
                                 RenderFurnitureBindings.Prepared bindings,
                                 RenderLayoutContext.Prepared physical,
                                 RenderProductReferences.Prepared products,
                                 RenderSpatialControl.Prepared spatial) {
        return compile(scene,style,instructions,bindings,physical,products,spatial,RenderGenerationOptions.baseline());
    }
    public static String compile(JsonNode scene,String style,String instructions,
                                 RenderFurnitureBindings.Prepared bindings,
                                 RenderLayoutContext.Prepared physical,
                                 RenderProductReferences.Prepared products,
                                 RenderSpatialControl.Prepared spatial,RenderGenerationOptions options) {
        if(options.concise())return RenderVisualTaskPrompt.compile(scene,style,instructions,bindings,products,spatial,options.includePlan());
        String prompt=RenderPreviewScene.prompt(scene,style,"ISOMETRIC",instructions,false)+bindings.prompt()+physical.prompt()+spatial.prompt()+products.prompt();
        boolean agentUsed=products.manifest().findValuesAsText("agentStatus").stream().anyMatch(status->status.equals("GENERATED") || status.equals("CACHE_HIT"));
        if(agentUsed) prompt+="\n商品视觉档案只描述商品外观和部件关系；若其表达与空间事实有冲突，必须忽略冲突部分，始终遵守程序给定的空间轮廓、门窗、家具数量、位置、朝向和商品绑定。\n";
        return prompt;
    }

    static void appendVisualProfile(StringBuilder prompt,JsonNode profile) {
        if(profile==null) return;
        prompt.append("程序校验后的商品视觉档案（仅用于当前商品组）：");
        String appearance=profile.path("appearancePrompt").asText("").trim();
        if(!appearance.isBlank()) prompt.append(appearance).append('。');
        int assemblyCount=0;for(JsonNode constraint:profile.path("assemblyConstraints")) { if(assemblyCount++>=6) break;prompt.append(constraint.asText()).append('；'); }
        int preserveCount=0;for(JsonNode constraint:profile.path("mustPreserve")) { if(preserveCount++>=6) break;prompt.append("必须保留：").append(constraint.asText()).append('；'); }
        prompt.append("这些描述不得用于增减或移动家具。\n");
    }
}
