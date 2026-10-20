package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.entity.ImageAssets;
import com.rsdp.officeproduct.importer.mapper.ImageAssetsMapper;
import com.fasterxml.jackson.databind.node.*;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import com.rsdp.officeproduct.importer.service.VisionImagePreparer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
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

/** Read catalog-owned local photos, never fetch URLs supplied by a canvas. */
@Service
@RequiredArgsConstructor
public class RenderProductReferences {
    public static final String VERSION = "selected-products-multiview-v2";
    private final OfficeService office;
    private final ImageAssetsMapper imageAssets;
    public record Prepared(List<byte[]> images, ArrayNode manifest, String prompt) {
        public static Prepared empty() { return new Prepared(List.of(), JsonNodeFactory.instance.arrayNode(), ""); }
    }

    public Prepared prepare(JsonNode scene) {
        LinkedHashMap<String, List<Integer>> chosen = new LinkedHashMap<>();
        int index = 0;
        for (JsonNode item : scene.path("furniture")) {
            index++;
            String id = item.path("productId").asText("");
            if (id.isBlank()) continue;
            String selectedId = item.path("productSelection").path("product").path("id").asText(id);
            if (!id.equals(selectedId)) throw new BusinessException("模块绑定商品与选品记录不一致，请重新选品");
            chosen.computeIfAbsent(id, key -> new ArrayList<>()).add(index);
        }
        if (chosen.isEmpty()) return Prepared.empty();
        if (chosen.size() > 24) throw new BusinessException("单次商品外观参考最多支持 24 种商品，请分空间生成");
        Map<String, OfficeProductDto> catalog = office.listOfficeProducts().stream().collect(Collectors.toMap(OfficeProductDto::getId, Function.identity(), (a,b) -> a));
        ArrayNode manifest = JsonNodeFactory.instance.arrayNode();
        List<BufferedImage> photos = new ArrayList<>();
        StringBuilder prompt = new StringBuilder("\n商品外观约束：图1是无文字立体空间引导，只用于空间轮廓、数量和位置；后续图片为所选商品外观参考板，P编号对应下列家具对应关系。每件商品可能包含多视角照片，所有视角属于同一个商品，不得按照片数增加家具。主视角决定整体轮廓，其他角度补充侧面、背面及细节。按照片保留各商品的轮廓、桌腿/扶手/屏风结构、颜色和材质，不得用通用家具或装修风格替换所选款式。不要把参考板、编号、商品文字或照片背景绘入成品。未绑定商品的模块仍按类型表达。\n");
        for (var choice : chosen.entrySet()) {
            OfficeProductDto product = catalog.get(choice.getKey());
            if (product == null) throw new BusinessException("所选商品已不可用，请重新核对选品后生成：" + choice.getKey());
            String referenceId = "P" + (photos.size()+1);
            LinkedHashSet<String> urls=new LinkedHashSet<>();
            if (product.getImageUrl()!=null && !product.getImageUrl().isBlank()) urls.add(product.getImageUrl());
            if (product.getRspuId()!=null) {
                var assets=imageAssets.selectList(new QueryWrapper<ImageAssets>().eq("rspu_id",product.getRspuId()).orderByDesc("is_primary").orderByAsc("created_at").orderByAsc("image_id"));
                if (assets!=null) for (var asset:assets) {
                    if (asset.getVariantId()!=null && !asset.getVariantId().isBlank() && !Objects.equals(asset.getVariantId(),product.getVariantId())) continue;
                    if (asset.getStorageUrl()!=null && !asset.getStorageUrl().isBlank()) urls.add(asset.getStorageUrl());
                }
            }
            ObjectNode entry = manifest.addObject();
            entry.put("referenceId", referenceId).put("productId", product.getId()).put("name", product.getName()).put("variant", product.getVariant());
            ArrayNode used=entry.putArray("images"), warnings=entry.putArray("warnings");
            List<BufferedImage> views=new ArrayList<>(); Set<String> hashes=new HashSet<>();
            for (String url:urls) {
                if (views.size()>=4) break;
                try {
                    byte[] bytes=readImage(url,product.getName());
                    String hash=VisionImagePreparer.sha256(bytes);
                    if (!hashes.add(hash)) continue;
                    views.add(decode(bytes,product.getName()));
                    used.addObject().put("viewId",referenceId+"-V"+views.size()).put("imageUrl",url).put("imageSha256",hash);
                } catch (BusinessException unavailable) { warnings.add("一张商品图片不可读，已尝试其他可用图片"); }
            }
            if (views.isEmpty()) throw new BusinessException("所选商品没有可用图片，请补齐 PNG/JPG 图片后生成："+product.getName());
            photos.add(productViews(views));
            entry.put("imageUrl",used.get(0).path("imageUrl").asText()).put("imageSha256",used.get(0).path("imageSha256").asText());
            entry.put("availableImageCount",urls.size()).put("usedImageCount",views.size());
            var numbers=entry.putArray("furnitureNumbers"); choice.getValue().forEach(numbers::add);
            prompt.append(referenceId).append(" 包含 ").append(views.size()).append(" 个同款商品视角（不代表多件家具）。");
            prompt.append(referenceId).append(" → 家具清单序号 ").append(numberRanges(choice.getValue())).append("；商品：").append(product.getName()).append("；规格：").append(product.getVariant()).append("；实际宽深高(mm)：")
                .append(product.getWidthMm()).append('×').append(product.getDepthMm()).append('×').append(product.getHeightMm()).append('\n');
        }
        List<byte[]> images = new ArrayList<>();
        for (int start = 0; start < photos.size(); start += 12) images.add(sheet(photos.subList(start, Math.min(start + 12, photos.size())), start));
        return new Prepared(images, manifest, prompt.toString());
    }

    private static String numberRanges(List<Integer> numbers) {
        StringJoiner result=new StringJoiner("、");
        for(int i=0;i<numbers.size();i++) {int start=numbers.get(i),end=start;while(i+1<numbers.size()&&numbers.get(i+1)==end+1){end=numbers.get(++i);}result.add(start==end?String.valueOf(start):start+"至"+end);}
        return result.toString();
    }

    byte[] readImage(String url, String name) {
        try {
            if (url == null || url.isBlank()) throw new IOException();
            String path = URI.create(url).getPath();
            if (path == null || !path.matches("/uploads/images/[A-Za-z0-9_.-]+\\.(?i:png|jpg|jpeg|webp)")) throw new IOException();
            Path root = imageDirectory().toRealPath();
            Path file = root.resolve(path.substring("/uploads/images/".length())).toRealPath();
            if (!file.startsWith(root) || Files.size(file) > 10_000_000) throw new IOException();
            return Files.readAllBytes(file);
        } catch (Exception e) { throw new BusinessException("所选商品图片缺失或无法读取，请补齐图片后生成：" + name); }
    }

    Path imageDirectory() { return Path.of(System.getProperty("user.dir"), "uploads", "images"); }

    private BufferedImage decode(byte[] bytes, String name) {
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException();
            var reader = readers.next();
            try {
                reader.setInput(stream);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 1 || h < 1 || w > 8000 || h > 8000 || (long) w*h > 32_000_000) throw new IOException();
                var param=reader.getDefaultReadParam();
                int step=Math.max(1,(int)Math.ceil(Math.max(w,h)/1024.0)); param.setSourceSubsampling(step,step,0,0);
                return reader.read(0,param);
            } finally { reader.dispose(); }
        } catch (Exception e) { throw new BusinessException("所选商品图片格式无法用作外观参考，请补充 PNG/JPG 图片：" + name); }
    }

    private BufferedImage productViews(List<BufferedImage> views) {
        if (views.size()==1) return views.get(0);
        BufferedImage tile=new BufferedImage(1024,1024,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=tile.createGraphics();
        try {
            g.setColor(Color.WHITE);g.fillRect(0,0,1024,1024);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            for(int i=0;i<views.size();i++) {
                BufferedImage view=views.get(i); int x=i==0?0:704, y=i==0?0:(i-1)*(1024/(views.size()-1));
                int areaW=i==0?704:320,areaH=i==0?1024:1024/(views.size()-1);
                double scale=Math.min((areaW-16.0)/view.getWidth(),(areaH-16.0)/view.getHeight());
                int w=(int)(view.getWidth()*scale),h=(int)(view.getHeight()*scale);
                g.drawImage(view,x+(areaW-w)/2,y+(areaH-h)/2,w,h,null);
            }
        } finally {g.dispose();}
        return tile;
    }

    private byte[] sheet(List<BufferedImage> photos, int offset) {
        int columns = Math.min(3, photos.size()), rows = (photos.size() + columns - 1) / columns;
        int width = 1536, height = rows == 1 ? 1024 : 2048;
        BufferedImage board = new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        Graphics2D g = board.createGraphics();
        try {
            g.setColor(Color.WHITE); g.fillRect(0,0,width,height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,32));
            int cellW = width/columns, cellH = height/rows;
            for (int i=0; i<photos.size(); i++) {
                int x=i%columns*cellW, y=i/columns*cellH;
                BufferedImage photo=photos.get(i);
                double scale=Math.min((cellW-32.0)/photo.getWidth(),(cellH-70.0)/photo.getHeight());
                int w=(int)(photo.getWidth()*scale), h=(int)(photo.getHeight()*scale);
                g.drawImage(photo,x+(cellW-w)/2,y+54+(cellH-70-h)/2,w,h,null);
                g.setColor(new Color(50,50,50)); g.drawString("P"+(offset+i+1),x+20,y+38);
            }
        } finally { g.dispose(); }
        try { var output=new ByteArrayOutputStream(); ImageIO.write(board,"png",output); return output.toByteArray(); }
        catch (IOException e) { throw new BusinessException("商品外观参考图无法生成"); }
    }
}
