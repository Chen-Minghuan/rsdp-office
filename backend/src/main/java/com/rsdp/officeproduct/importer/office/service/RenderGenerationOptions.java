package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;

/** Persisted generation options. Omission selects the final E pipeline; explicit options retain A/B/C/D for regression comparisons. */
public record RenderGenerationOptions(PromptMode promptMode,ReferenceMode referenceMode,boolean promptExtend,Long seed,Integer sampleIndex) {
    public static final String EXPERIMENT_VERSION="selected-products-multiview-v11-prompt-ab";
    public enum PromptMode { TECHNICAL_V10,CONCISE_V11 }
    public enum ReferenceMode { GUIDE_PLAN_PRODUCT,GUIDE_PRODUCT }
    public static RenderGenerationOptions baseline() { return new RenderGenerationOptions(PromptMode.TECHNICAL_V10,ReferenceMode.GUIDE_PLAN_PRODUCT,true,null,null); }
    public static RenderGenerationOptions from(String promptMode,String referenceMode,Boolean promptExtend,Long seed,Integer sampleIndex) {
        PromptMode prompt=parse(PromptMode.class,promptMode,PromptMode.TECHNICAL_V10,"提示词模式");
        ReferenceMode references=parse(ReferenceMode.class,referenceMode,ReferenceMode.GUIDE_PLAN_PRODUCT,"参考图模式");
        boolean extend=promptExtend==null||promptExtend;
        if(seed!=null&&(seed<0||seed>2147483647L))throw new BusinessException("实验种子须在0～2147483647之间");
        if(sampleIndex!=null&&(sampleIndex<1||sampleIndex>999))throw new BusinessException("实验样本序号须在1～999之间");
        if(prompt==PromptMode.TECHNICAL_V10&&references!=ReferenceMode.GUIDE_PLAN_PRODUCT)throw new BusinessException("完整技术提示词实验必须使用三图参考");
        return new RenderGenerationOptions(prompt,references,extend,seed,sampleIndex);
    }
    private static <T extends Enum<T>> T parse(Class<T> type,String value,T fallback,String label) {
        if(value==null||value.isBlank())return fallback;
        try{return Enum.valueOf(type,value);}catch(IllegalArgumentException e){throw new BusinessException(label+"无效");}
    }
    public boolean concise() { return promptMode==PromptMode.CONCISE_V11; }
    public boolean explicit() { return concise()||!includePlan()||promptExtend||seed!=null||sampleIndex!=null; }
    public boolean includePlan() { return referenceMode==ReferenceMode.GUIDE_PLAN_PRODUCT; }
    public String group() { if(!concise())return promptExtend?"E":"A";if(!includePlan())return"C";return promptExtend?"D":"B"; }
    public String promptVersion() { return concise()?"visual-task-v1":"technical-v10"; }
    public ObjectNode manifest() {
        ObjectNode result=JsonNodeFactory.instance.objectNode();result.put("promptMode",promptMode.name()).put("promptVersion",promptVersion()).put("referenceMode",referenceMode.name())
            .put("promptExtend",promptExtend).put("sampleGroup",group());
        if(promptExtend)result.put("promptExtendMode","direct");else result.putNull("promptExtendMode");
        if(seed==null)result.putNull("seed");else result.put("seed",seed);if(sampleIndex==null)result.putNull("sampleIndex");else result.put("sampleIndex",sampleIndex);return result;
    }
}
