package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.RsdpMainProperties;
import com.rsdp.officeproduct.importer.dto.response.ProductDetailDto;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import com.rsdp.officeproduct.importer.service.RsdpCatalogGateway;
import com.rsdp.officeproduct.importer.service.VisionImagePreparer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Product reference assembly with deterministic F/P facts and optional cached visual understanding. */
@Service
@RequiredArgsConstructor
public class RenderProductReferences {
    public static final String VERSION = "selected-products-multiview-v10-clean-spatial-observer";
    private final OfficeService office;
    private final RsdpCatalogGateway catalogGateway;
    private final RestTemplate restTemplate;
    private final RsdpMainProperties mainProperties;
    private final ProductVisualUnderstandingService understanding;

    public record Prepared(List<byte[]> images, ArrayNode manifest, String prompt,ObjectNode understanding) {
        public Prepared(List<byte[]> images,ArrayNode manifest,String prompt) { this(images,manifest,prompt,emptyUnderstanding()); }
        public static Prepared empty() { return new Prepared(List.of(), JsonNodeFactory.instance.arrayNode(), "",emptyUnderstanding()); }
        private static ObjectNode emptyUnderstanding() { return JsonNodeFactory.instance.objectNode().put("status","NOT_REQUIRED"); }
    }
    private record CandidateImage(ProductVisualUnderstandingService.SourceImage source,BufferedImage image) { }
    private record ProductGroupImage(String referenceId,BufferedImage image) { }
    private record PreparedView(ProductVisualUnderstandingService.SourceImage source,BufferedImage image,RenderSubjectCropper.Result isolation) { }
    private record CandidateProduct(OfficeProductDto product,String referenceId,List<RenderFurnitureBindings.Binding> bindings,
                                    List<CandidateImage> images,List<String> warnings,int availableImageCount) { }

    public Prepared prepare(JsonNode scene) { return prepare(scene,RenderFurnitureBindings.from(scene)); }

    /** Compatibility overload; new rendering always uses the shared semantic bindings. */
    @Deprecated
    public Prepared prepare(JsonNode scene, boolean guided) { return prepare(scene); }

    public Prepared prepare(JsonNode scene, RenderFurnitureBindings.Prepared bindings) {
        Map<String,JsonNode> furnitureById=new LinkedHashMap<>();
        Map<String,Integer> furnitureNumbers=new LinkedHashMap<>();
        int furnitureIndex=0;
        for(JsonNode item:scene.path("furniture")) { furnitureIndex++;furnitureById.put(item.path("id").asText(),item);furnitureNumbers.put(item.path("id").asText(),furnitureIndex); }
        LinkedHashMap<String,List<RenderFurnitureBindings.Binding>> chosen=new LinkedHashMap<>();
        for(var binding:bindings.items()) {
            if(binding.productId().isBlank()) continue;
            JsonNode item=furnitureById.get(binding.furnitureId());
            if(item==null || !binding.productId().equals(item.path("productId").asText())) throw new BusinessException("家具商品绑定关系已变化，请重新保存排版后生成");
            String selectedId=item.path("productSelection").path("product").path("id").asText(binding.productId());
            if(!binding.productId().equals(selectedId)) throw new BusinessException("模块绑定商品与选品记录不一致，请重新选品");
            chosen.computeIfAbsent(binding.productId(),key->new ArrayList<>()).add(binding);
        }
        if(chosen.isEmpty()) return Prepared.empty();
        if(chosen.size()>12) throw new BusinessException("三图空间控制模式单次最多支持 12 种商品，请分空间生成");
        Map<String,OfficeProductDto> catalog=office.listOfficeProducts().stream().collect(Collectors.toMap(OfficeProductDto::getId,Function.identity(),(a,b)->a));

        List<CandidateProduct> candidates=new ArrayList<>();
        for(var choice:chosen.entrySet()) candidates.add(candidates(choice.getKey(),choice.getValue(),catalog));
        var batch=understanding.understandAll(candidates.stream().map(this::request).toList());

        ArrayNode manifest=JsonNodeFactory.instance.arrayNode();
        List<ProductGroupImage> photos=new ArrayList<>();
        StringBuilder prompt=new StringBuilder("\n商品外观约束：图1和图2只负责空间与布局；图3为带P编号的所选商品外观参考板，不提供数量、位置或房间设计。参考板中的商品组按从左到右、从上到下排列；每件商品可能包含多视角照片，所有视角属于同一个商品，不得按照片数增加家具。主视角决定整体轮廓，其他角度补充侧面、背面及细节。按照片保留各商品的轮廓、桌腿/扶手/屏风结构、柜体位置、颜色和材质，不得用通用家具或装修风格替换所选款式。不要把参考板、编号、商品文字或照片背景绘入成品。未绑定商品的模块仍按类型表达。\n"
            + "多视角与家具结构约束：参考板中图片的左右位置、大小和排列顺序不表示家具的正面、背面或侧面。参考所有视角理解同一件家具的完整外观，根据图1的空间视角和家具朝向，只自然呈现当前可见的一侧；不可见部分不得翻到可见面，不得同时暴露商品所有方向，不得把多张参考照片拼贴或融合成一件扭曲家具。优先生成结构完整、稳定、可正常使用的家具，再赋予商品外观特征。每件家具必须是一个连贯的三维实体，各部件具有合理的连接和支撑关系；水平受力面保持连续和平稳，竖向支撑合理连接；柜体、挡板、开放格、桌腿或扶手只能位于其所属面。不得出现部件重复、交叉、悬空、断裂、翻面或正反结构叠加。\n");
        int productIndex=0;
        for(CandidateProduct candidate:candidates) {
            int imageNumber=3,groupNumber=productIndex+1;productIndex++;
            OfficeProductDto product=candidate.product();String referenceId=candidate.referenceId();
            var agentResult=batch.forProduct(product.getId());
            if(agentResult!=null && "INVALID_RESPONSE".equals(agentResult.status()))
                throw new BusinessException("商品参考图构成或目标未通过校验，请上传清晰单品图或核对套组目标："+product.getName());
            ObjectNode profile=agentResult!=null && agentResult.usable()?agentResult.profile():null;
            var sources=candidate.images().stream().map(CandidateImage::source).toList();
            var selection=RenderReferencePlanner.select(sources,profile,product.getName());
            Map<String,CandidateImage> decodedById=candidate.images().stream().collect(Collectors.toMap(image->image.source().imageId(),Function.identity()));
            List<PreparedView> views=new ArrayList<>();
            for(var source:selection.images()) {
                CandidateImage sourceImage=decodedById.get(source.imageId());
                var isolation=RenderSubjectCropper.prepare(sourceImage.image(),profileView(profile,source),product.getName(),profile!=null);
                views.add(new PreparedView(source,isolation.image(),isolation));
            }
            photos.add(new ProductGroupImage(referenceId,productViews(views.stream().map(PreparedView::image).toList())));

            ObjectNode entry=manifest.addObject();
            entry.put("referenceId",referenceId).put("productId",product.getId()).put("name",product.getName()).put("variant",product.getVariant());
            entry.put("agentStatus",agentResult==null?"UNAVAILABLE":agentResult.status());
            if(profile!=null) entry.set("visualProfile",profile);
            ArrayNode allImages=entry.putArray("candidateImages");candidate.images().forEach(image->imageMetadata(allImages,image.source()));
            ArrayNode used=entry.putArray("images");views.forEach(image->imageMetadata(used,image));
            ArrayNode warnings=entry.putArray("warnings");candidate.warnings().forEach(warnings::add);selection.warnings().forEach(warnings::add);
            views.forEach(image->image.isolation().warnings().forEach(warnings::add));
            if(agentResult!=null) agentResult.warnings().forEach(warnings::add);
            entry.put("imageUrl",selection.images().get(0).imageUrl()).put("imageSha256",selection.images().get(0).imageSha256());
            entry.put("generationImageSha256",used.path(0).path("generationImageSha256").asText());
            entry.put("availableImageCount",candidate.availableImageCount()).put("candidateImageCount",candidate.images().size()).put("usedImageCount",selection.images().size());
            entry.put("quantity",candidate.bindings().size());
            var numbers=entry.putArray("furnitureNumbers");candidate.bindings().forEach(binding->numbers.add(furnitureNumbers.get(binding.furnitureId())));
            var furnitureReferences=entry.putArray("furnitureReferenceIds");candidate.bindings().forEach(binding->furnitureReferences.add(binding.referenceId()));
            String furnitureDescriptions=candidate.bindings().stream().map(binding->binding.referenceId()+"→"+binding.productReferenceId()+"（位于"+binding.location()+"的"+binding.type()+"）").collect(Collectors.joining("、"));
            prompt.append("图").append(imageNumber).append("第").append(groupNumber).append("个商品组包含 ").append(views.size()).append(" 个同款商品视角（不代表多件家具），该商品外观使用数量为").append(candidate.bindings().size()).append("件。");
            prompt.append("该商品组 → ").append(furnitureDescriptions).append("；这些家具必须使用此商品组，不得替换为通用家具或应用到其他家具；参考其整体轮廓、颜色、材质和部件结构。\n");
            RenderPromptCompiler.appendVisualProfile(prompt,profile);
        }
        List<byte[]> images=new ArrayList<>();
        for(int start=0;start<photos.size();start+=12) images.add(sheet(photos.subList(start,Math.min(start+12,photos.size()))));
        return new Prepared(images,manifest,prompt.toString(),batch.manifest(understanding.maxNewProductsPerRender()));
    }

    private CandidateProduct candidates(String productId,List<RenderFurnitureBindings.Binding> bindings,Map<String,OfficeProductDto> catalog) {
        OfficeProductDto product=catalog.get(productId);
        if(product==null) throw new BusinessException("所选商品已不可用，请重新核对选品后生成："+productId);
        String referenceId=bindings.get(0).productReferenceId();
        LinkedHashSet<String> urls=new LinkedHashSet<>();
        if(product.getImageUrl()!=null && !product.getImageUrl().isBlank()) urls.add(product.getImageUrl());
        if(product.getRspuId()!=null) {
            List<ProductDetailDto.Image> assets=catalogGateway.getProductDetail(product.getRspuId()).getImages();
            if(assets!=null) for(var asset:assets) {
                if(asset.getVariantId()!=null && !asset.getVariantId().isBlank() && !Objects.equals(asset.getVariantId(),product.getVariantId())) continue;
                if(asset.getStorageUrl()!=null && !asset.getStorageUrl().isBlank()) urls.add(asset.getStorageUrl());
            }
        }
        List<CandidateImage> images=new ArrayList<>();List<String> warnings=new ArrayList<>();Set<String> hashes=new HashSet<>();
        for(String url:urls) {
            if(images.size()>=4) break;
            try {
                byte[] bytes=readImage(url,product.getName());String hash=VisionImagePreparer.sha256(bytes);
                if(!hashes.add(hash)) continue;
                BufferedImage decoded=decode(bytes,product.getName());String imageId="IMG-"+hash.substring(0,16);
                images.add(new CandidateImage(new ProductVisualUnderstandingService.SourceImage(imageId,url,hash,png(decoded,product.getName())),decoded));
            } catch(BusinessException unavailable) { warnings.add("一张商品图片不可读，已尝试其他可用图片"); }
        }
        if(images.isEmpty()) throw new BusinessException("所选商品没有可用图片，请补齐 PNG/JPG 图片后生成："+product.getName());
        return new CandidateProduct(product,referenceId,bindings,images,warnings,urls.size());
    }

    private ProductVisualUnderstandingService.Request request(CandidateProduct candidate) {
        OfficeProductDto product=candidate.product();
        return new ProductVisualUnderstandingService.Request(product.getId(),product.getRspuId(),product.getVariantId(),product.getName(),
            candidate.images().stream().map(CandidateImage::source).toList());
    }
    private static void imageMetadata(ArrayNode target,ProductVisualUnderstandingService.SourceImage image) {
        target.addObject().put("viewId",image.imageId()).put("imageUrl",image.imageUrl()).put("imageSha256",image.imageSha256());
    }
    private void imageMetadata(ArrayNode target,PreparedView view) {
        ObjectNode entry=target.addObject().put("viewId",view.source().imageId()).put("imageUrl",view.source().imageUrl())
            .put("imageSha256",view.source().imageSha256()).put("sourceImageSha256",view.source().imageSha256())
            .put("generationImageSha256",VisionImagePreparer.sha256(png(view.image(),view.source().imageId())))
            .put("isolationMethod",view.isolation().method()).put("requiresManualReview",!view.isolation().warnings().isEmpty());
        var sourceBbox=entry.putArray("targetBbox");view.isolation().sourceBbox().forEach(sourceBbox::add);
        var crop=entry.putArray("appliedCropPixels");view.isolation().appliedCropPixels().forEach(crop::add);
    }
    private static JsonNode profileView(JsonNode profile,ProductVisualUnderstandingService.SourceImage source) {
        if(profile==null) return null;
        for(JsonNode view:profile.path("views")) if(source.imageId().equals(view.path("imageId").asText())) return view;
        for(JsonNode view:profile.path("views")) if(source.imageSha256().equals(view.path("imageSha256").asText())) return view;
        return null;
    }

    byte[] readImage(String url,String name) {
        try {
            if(url==null || url.isBlank()) throw new IOException();
            if(isMainImageUrl(url)) {
                ResponseEntity<byte[]> response=restTemplate.getForEntity(url,byte[].class);byte[] body=response.getBody();
                if(body==null || body.length==0 || body.length>10_000_000) throw new IOException();
                return body;
            }
            String path=URI.create(url).getPath();
            if(path==null || !path.matches("/uploads/images/[A-Za-z0-9_.-]+\\.(?i:png|jpg|jpeg|webp)")) throw new IOException();
            Path root=imageDirectory().toRealPath();Path file=root.resolve(path.substring("/uploads/images/".length())).toRealPath();
            if(!file.startsWith(root) || Files.size(file)>10_000_000) throw new IOException();
            return Files.readAllBytes(file);
        } catch(BusinessException e) { throw e; }
        catch(Exception e) { throw new BusinessException("所选商品图片缺失或无法读取，请补齐图片后生成："+name,e); }
    }

    private boolean isMainImageUrl(String url) {
        String base=mainProperties.getBaseUrl();if(base==null || base.isBlank()) return false;
        return url.startsWith(base.replaceAll("/+$","")+"/api/v1/images/");
    }
    Path imageDirectory() { return Path.of(System.getProperty("user.dir"),"uploads","images"); }

    private BufferedImage decode(byte[] bytes,String name) {
        try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext()) throw new IOException();
            var reader=readers.next();
            try {
                reader.setInput(stream);int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<1 || h<1 || w>8000 || h>8000 || (long)w*h>32_000_000) throw new IOException();
                var param=reader.getDefaultReadParam();int step=Math.max(1,(int)Math.ceil(Math.max(w,h)/1024.0));param.setSourceSubsampling(step,step,0,0);
                return reader.read(0,param);
            } finally { reader.dispose(); }
        } catch(Exception e) { throw new BusinessException("所选商品图片格式无法用作外观参考，请补充 PNG/JPG 图片："+name); }
    }
    private byte[] png(BufferedImage image,String name) {
        try { var output=new ByteArrayOutputStream();ImageIO.write(image,"png",output);return output.toByteArray(); }
        catch(IOException e) { throw new BusinessException("商品理解参考图转换失败："+name); }
    }

    private BufferedImage productViews(List<BufferedImage> views) {
        if(views.size()==1) return views.get(0);
        BufferedImage tile=new BufferedImage(1024,1024,BufferedImage.TYPE_INT_RGB);Graphics2D g=tile.createGraphics();
        try {
            g.setColor(Color.WHITE);g.fillRect(0,0,1024,1024);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            for(int i=0;i<views.size();i++) {
                BufferedImage view=views.get(i);int x=i==0?0:704,y=i==0?0:(i-1)*(1024/(views.size()-1));
                int areaW=i==0?704:320,areaH=i==0?1024:1024/(views.size()-1);
                double scale=Math.min((areaW-16.0)/view.getWidth(),(areaH-16.0)/view.getHeight());int w=(int)(view.getWidth()*scale),h=(int)(view.getHeight()*scale);
                g.drawImage(view,x+(areaW-w)/2,y+(areaH-h)/2,w,h,null);
            }
        } finally { g.dispose(); }
        return tile;
    }

    private byte[] sheet(List<ProductGroupImage> photos) {
        int columns=Math.min(3,photos.size()),rows=(photos.size()+columns-1)/columns;
        int width=1536,height=rows==1?1024:2048;BufferedImage board=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);Graphics2D g=board.createGraphics();
        try {
            g.setColor(Color.WHITE);g.fillRect(0,0,width,height);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            int cellW=width/columns,cellH=height/rows;
            for(int i=0;i<photos.size();i++) {
                int x=i%columns*cellW,y=i/columns*cellH;ProductGroupImage group=photos.get(i);BufferedImage photo=group.image();
                g.setColor(new Color(35,55,65));g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,30));g.drawString(group.referenceId(),x+16,y+38);
                double scale=Math.min((cellW-32.0)/photo.getWidth(),(cellH-70.0)/photo.getHeight());int w=(int)(photo.getWidth()*scale),h=(int)(photo.getHeight()*scale);
                g.drawImage(photo,x+(cellW-w)/2,y+54+(cellH-70-h)/2,w,h,null);
            }
        } finally { g.dispose(); }
        try { var output=new ByteArrayOutputStream();ImageIO.write(board,"png",output);return output.toByteArray(); }
        catch(IOException e) { throw new BusinessException("商品外观参考图无法生成"); }
    }

}
