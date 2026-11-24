package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/** Deterministic crop of an agent-located target. This is not pixel-level segmentation. */
public final class RenderSubjectCropper {
    private static final double MIN_TARGET_CONFIDENCE=.8;
    private static final double PADDING_RATIO=.04;
    private RenderSubjectCropper() {}

    public record Result(BufferedImage image,String method,List<Integer> sourceBbox,List<Integer> appliedCropPixels,List<String> warnings) {
        public static Result original(BufferedImage image) {
            return new Result(image,"NONE",List.of(),List.of(),List.of());
        }
    }

    public static Result prepare(BufferedImage source,JsonNode view,String productName) {
        return prepare(source,view,productName,false);
    }
    public static Result prepare(BufferedImage source,JsonNode view,String productName,boolean agentProfileAvailable) {
        if(view==null || view.isMissingNode()) {
            if(agentProfileAvailable) throw new BusinessException("商品图片与视觉档案无法可靠对应，请重新理解商品图片或上传清晰单品图："+productName);
            return Result.original(source);
        }
        boolean requires=view.path("requiresSubjectIsolation").asBoolean(false);
        if(!view.has("originalImageSuitableForDirectGeneration") && !requires) return Result.original(source);
        boolean direct=view.path("originalImageSuitableForDirectGeneration").asBoolean(false);
        if(direct && !requires) return Result.original(source);
        if(!requires) throw new BusinessException("商品原图不能直接用于生图，且没有可靠的主体隔离方式："+productName);
        JsonNode target=view.path("target");
        if(!"IDENTIFIED".equals(target.path("status").asText()) || target.path("confidence").asDouble()<MIN_TARGET_CONFIDENCE)
            throw new BusinessException("套组图片中的目标商品不够明确，请上传单品图或人工确认目标："+productName);
        JsonNode bbox=target.path("bbox");
        if(bbox.size()!=4) throw new BusinessException("套组图片缺少可靠目标区域，请上传单品图或人工框选："+productName);
        double left=bbox.get(0).asDouble()/1000*source.getWidth(),top=bbox.get(1).asDouble()/1000*source.getHeight();
        double right=bbox.get(2).asDouble()/1000*source.getWidth(),bottom=bbox.get(3).asDouble()/1000*source.getHeight();
        double padX=(right-left)*PADDING_RATIO,padY=(bottom-top)*PADDING_RATIO;
        int x=(int)Math.max(0,Math.floor(left-padX)),y=(int)Math.max(0,Math.floor(top-padY));
        int r=(int)Math.min(source.getWidth(),Math.ceil(right+padX)),b=(int)Math.min(source.getHeight(),Math.ceil(bottom+padY));
        int w=r-x,h=b-y;
        if(w<16 || h<16 || (long)w*h<(long)source.getWidth()*source.getHeight()/100)
            throw new BusinessException("商品目标区域过小或无效，请上传单品图或人工框选："+productName);
        BufferedImage cropped=new BufferedImage(w,h,source.getType()==BufferedImage.TYPE_CUSTOM?BufferedImage.TYPE_INT_RGB:source.getType());
        Graphics2D g=cropped.createGraphics();
        try { g.drawImage(source.getSubimage(x,y,w,h),0,0,null); } finally { g.dispose(); }
        List<Integer> normalized=new ArrayList<>();bbox.forEach(value->normalized.add(value.asInt()));
        return new Result(cropped,"TARGET_BBOX_CROP",List.copyOf(normalized),List.of(x,y,r,b),
            List.of("已按智能体目标框裁剪；这不是像素级分割，框内仍可能包含配套商品，需人工核对"));
    }
}
