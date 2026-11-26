package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.rsdp.officeproduct.importer.common.BusinessException;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.List;

/** Millimetre geometry, independent of canvas pan/zoom and of recognition originals. */
public final class RenderPreviewScene {
    private RenderPreviewScene() {}
    // Keep these projections in sync with frontend/app/utils/renderPreview.ts.
    private static final List<String> ZONE_FIELDS = List.of("id", "name", "zoneType", "reviewUnit", "polygon", "obstacles", "evidenceKind", "sourceAnalysisId", "sourceAnalysisRevision", "spaceTemplateId", "templateSpaceId", "sourceImageSha256", "mmPerSourceUnit", "sourceYAxis", "layoutPreferences");
    private static final List<String> FURNITURE_FIELDS = List.of("id", "zoneId", "x", "y", "w", "h", "rotation", "heightMm", "wingWidthMm", "label", "furnitureTemplateId", "moduleSnapshot", "productId", "productSelection", "mirrorX", "layoutSettings", "layoutGroupId", "layoutClusterId", "layoutRole");

    public static ObjectNode snapshot(JsonNode objects, String zoneId) {
        if (objects == null || !objects.isArray()) throw new BusinessException("请先保存空间排版");
        List<JsonNode> zones = new ArrayList<>(), items = new ArrayList<>();
        for (JsonNode item : objects) {
            if ("zone".equals(item.path("kind").asText()) && zoneId.equals(item.path("id").asText())) zones.add(item);
            if ("furniture".equals(item.path("kind").asText()) && zoneId.equals(item.path("zoneId").asText())) items.add(item);
        }
        if (zones.size() != 1) throw new BusinessException("空间不存在或 ID 重复");
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        result.set("zone", project(zones.get(0), ZONE_FIELDS));
        ArrayNode furniture = result.putArray("furniture");
        items.stream().sorted(Comparator.comparing(item -> item.path("id").asText())).forEach(item -> furniture.add(project(item, FURNITURE_FIELDS)));
        return (ObjectNode) canonical(result);
    }

    private static ObjectNode project(JsonNode source, List<String> keys) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        keys.forEach(key -> { if (source.hasNonNull(key)) result.set(key, source.get(key)); });
        return result;
    }

    public static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            TreeSet<String> keys = new TreeSet<>(); value.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> result.set(key, canonical(value.get(key)))); return result;
        }
        if (value.isArray()) { ArrayNode result = JsonNodeFactory.instance.arrayNode(); value.forEach(item -> result.add(canonical(item))); return result; }
        if (value.isNumber()) return DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());
        return value.deepCopy();
    }

    public static void validate(JsonNode scene) {
        JsonNode zone = scene.path("zone"), ring = zone.path("polygon"), items = scene.path("furniture");
        if (ring.size() > 2000 || items.size() > 100) throw new BusinessException("单次效果预览最多支持 100 个家具模块和 2000 个空间顶点");
        ObjectNode checked = zone.deepCopy(); checked.put("kind", "zone");
        OfficeCanvasReviewPolicy.requireLayoutReady(JsonNodeFactory.instance.arrayNode().add(checked), zone.path("id").asText());
        for (JsonNode point : ring) for (JsonNode coordinate : point) number(coordinate, "空间坐标", -100_000_000, 100_000_000);
        if (items.isEmpty()) throw new BusinessException("请先在当前空间摆放家具，再生成效果预览");
        Area room = new Area(roomPath(ring));
        Set<String> ids = new HashSet<>();
        List<Area> footprints = new ArrayList<>();
        for (JsonNode item : items) {
            if (item.path("id").asText().isBlank() || !ids.add(item.path("id").asText())) throw new BusinessException("家具 ID 缺失或重复");
            number(item.get("x"), "家具位置", -100_000_000, 100_000_000);
            number(item.get("y"), "家具位置", -100_000_000, 100_000_000);
            number(item.get("w"), "家具宽度", 10, 100_000);
            number(item.get("h"), "家具进深", 10, 100_000);
            number(item.get("rotation"), "家具旋转角度", -360, 360);
            if (item.hasNonNull("heightMm")) number(item.get("heightMm"), "家具高度", 1, 100_000);
            Area footprint = new Area(furnitureShape(item));
            Area outside = new Area(footprint); outside.subtract(room);
            if (area(outside) > 1) throw new BusinessException("家具超出当前空间，请调整排版后生成");
            for (int i = 0; i < footprints.size(); i++) {
                Area intersection = new Area(footprint); intersection.intersect(footprints.get(i));
                if (area(intersection) > 1) {
                    throw new BusinessException(OfficeLayoutValidationPolicy.furnitureOverlapMessage(zone, items.get(i), item) + "，请调整排版后生成");
                }
            }
            footprints.add(footprint);
        }
    }

    private static void number(JsonNode value, String label, double min, double max) {
        if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() < min || value.asDouble() > max) throw new BusinessException(label + "无效或超出可预览范围");
    }

    public static Path2D roomPath(JsonNode ring) {
        Path2D path = new Path2D.Double();
        for (int i=0; i<ring.size(); i++) { JsonNode p=ring.get(i); if (i==0) path.moveTo(p.get(0).asDouble(),p.get(1).asDouble()); else path.lineTo(p.get(0).asDouble(),p.get(1).asDouble()); }
        path.closePath(); return path;
    }

    public static Shape furnitureShape(JsonNode item) {
        double x=item.path("x").asDouble(), y=item.path("y").asDouble(), w=item.path("w").asDouble(), h=item.path("h").asDouble();
        String template=item.path("moduleSnapshot").path("glyphTemplateId").asText(item.path("furnitureTemplateId").asText());
        String kind=item.path("moduleSnapshot").path("kind").asText("");
        boolean lDesk=List.of("desk-l","desk-l-rounded","desk-screen-l","desk-manager-l","desk-executive-l").contains(template)||List.of("l-desk","rounded-l-desk").contains(kind);
        Path2D.Double local=new Path2D.Double();
        if(lDesk) {
            double wing=Math.min(item.path("wingWidthMm").asDouble(550),Math.min(w/2,h/2)),depth=Math.min(h*.48,template.startsWith("desk-executive")?800:650);
            local.moveTo(0,0);local.lineTo(w,0);local.lineTo(w,h);local.lineTo(w-wing,h);local.lineTo(w-wing,depth);local.lineTo(0,depth);
        } else {
            local.moveTo(0,0);local.lineTo(w,0);local.lineTo(w,h);local.lineTo(0,h);
        }
        local.closePath();
        Shape shaped=local;
        if(item.path("mirrorX").asBoolean()) shaped=AffineTransform.getScaleInstance(-1,1).createTransformedShape(local);
        AffineTransform place=new AffineTransform();place.translate(x+w/2,y+h/2);place.rotate(Math.toRadians(item.path("rotation").asDouble()));
        place.translate(item.path("mirrorX").asBoolean()?w/2:-w/2,-h/2);
        return place.createTransformedShape(shaped);
    }

    private static double area(Area shape) {
        double sum=0, firstX=0, firstY=0, x=0, y=0; double[] p=new double[6];
        for (PathIterator it=shape.getPathIterator(null); !it.isDone(); it.next()) {
            int t=it.currentSegment(p);
            if (t==PathIterator.SEG_MOVETO) { firstX=x=p[0]; firstY=y=p[1]; }
            else if (t==PathIterator.SEG_LINETO) { sum+=x*p[1]-p[0]*y; x=p[0]; y=p[1]; }
            else if (t==PathIterator.SEG_CLOSE) sum+=x*firstY-firstX*y;
        }
        return Math.abs(sum)/2;
    }

    public static byte[] referencePng(JsonNode scene) {
        BufferedImage image = new BufferedImage(1536,1024,BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE); g.fillRect(0,0,1536,1024);
            Shape room=roomPath(scene.path("zone").path("polygon")); Rectangle2D bounds=room.getBounds2D();
            double scale=Math.min(1376/bounds.getWidth(),824/bounds.getHeight());
            g.translate((1536-bounds.getWidth()*scale)/2-bounds.getX()*scale,(1024-bounds.getHeight()*scale)/2-bounds.getY()*scale);
            g.scale(scale,scale);
            g.setColor(new Color(246,243,237)); g.fill(room);
            g.setColor(new Color(55,55,55)); g.setStroke(new BasicStroke((float)(5/scale))); g.draw(room);
            for (JsonNode item : scene.path("furniture")) {
                Shape footprint=furnitureShape(item);
                g.setColor(new Color(207,219,224)); g.fill(footprint);
                g.setColor(new Color(54,80,94)); g.setStroke(new BasicStroke((float)(2/scale))); g.draw(footprint);
                // No labels or direction marks: an image-conditioned model may copy them into the finished image.
                // Rotation is represented by the footprint and by the accompanying millimetre list.
            }
        } finally { g.dispose(); }
        try { ByteArrayOutputStream bytes=new ByteArrayOutputStream(); ImageIO.write(image,"png",bytes); return bytes.toByteArray(); }
        catch (IOException e) { throw new BusinessException("布局参考图生成失败"); }
    }

    public static String prompt(JsonNode scene, String style, String view, String instructions) {
        return prompt(scene, style, view, instructions, false);
    }

    /**
     * guided（whiteModelView）：图1为白模实时相机截图，视角/透视/结构由截图锁定，模型只替换材质与光影；
     * 否则图1为带F编号的立体结构引导，由 view 决定机位。
     */
    public static String prompt(JsonNode scene, String style, String view, String instructions, boolean guided) {
        StringBuilder text=new StringBuilder("生成一张满幅、照片级写实的3D室内效果图，材质真实细腻，光影自然，透视准确，结构合理，构图饱满，细节精致。严格遵循输入参考，不输出文字、标注、图表或拼贴。\n");
        if (guided) text.append("图1为当前办公空间 3D 白模的实时相机截图（简化材质的占位模型，不是要放入场景的图片或实体）；严格保持其相机视角、透视关系、墙体轮廓、门窗位置与洞口以及家具的数量、位置、朝向和尺寸比例不变，仅把白模块体替换为真实材质与真实办公家具，补充地面、墙面、自然光照与软装氛围，让地面、墙体和家具处于同一透视空间中。\n");
        else text.append("图1为无文字的45度空间结构参考，决定最终相机、透视和立体结构；图2为同一空间的无文字平面控制图，只用于家具地面位置、数量、朝向、占地和通道关系，不得采用图2的正俯视相机或把成品画成平面图。文字清单中的F/P编号只用于说明映射，不是参考图中的可见文字。先按照图2、空间坐标和F/P映射确定每个F块体的家具身份与商品外观（如有），再补充真实材质、自然光照、阴影、墙面和地面质感。\n");
        text.append("严格保持结构参考中的空间轮廓、家具数量、相对位置、尺寸比例及朝向，不擅自增删、移动或改换家具类型；商品外观替换仅按F/P映射执行。\n");
        if (guided) text.append("视角：与图1截图完全一致，不得改为其他机位、俯视或纯鸟瞰角度。");
        else text.append("视角：").append("ISOMETRIC".equals(view) ? "45度斜俯视鸟瞰，移除遮挡视线的近侧墙和天花，完整展示排版。" : "室内人眼高度广角透视，保持空间和家具关系，合理选择能看见主要家具的机位。");
        text.append("\n风格：").append(style).append("。自然柔和光照，清晰细腻。空间：").append(scene.path("zone").path("name").asText()).append("。\n");
        text.append("未选商品的模块按家具类型表达，不宣称还原真实商品。结构参考中的各块仅表示对应家具的位置。最终只输出一个完整空间画面，不输出F/P编号、标签、参考板、拼贴或中间步骤。\n");
        TreeMap<String,Integer> types=new TreeMap<>();
        for(JsonNode item:scene.path("furniture")) {
            String label=item.path("label").asText(item.path("furnitureTemplateId").asText("办公家具"));
            types.merge(label,1,Integer::sum);
        }
        text.append("模块构成：");types.forEach((label,count)->text.append(label.substring(0,Math.min(label.length(),48))).append("共").append(count).append("件；"));text.append('\n');
        if (!instructions.isBlank()) text.append("补充偏好（不得覆盖布局约束）：").append(instructions);
        return text.toString();
    }
}
