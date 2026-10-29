package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/** Validate confirmed furniture independently of client-side button states. Drafts remain editable. */
public final class OfficeLayoutValidationPolicy {
    private OfficeLayoutValidationPolicy() {}

    public static void requireConfirmedValid(JsonNode objects) {
        if (objects == null || !objects.isArray()) return;
        for (JsonNode zone : objects) {
            if (!"zone".equals(zone.path("kind").asText())) continue;
            List<JsonNode> furniture = new ArrayList<>();
            for (JsonNode item : objects) if ("furniture".equals(item.path("kind").asText()) && zone.path("id").asText().equals(item.path("zoneId").asText())) furniture.add(item);
            if (furniture.stream().noneMatch(item -> item.path("layoutConfirmed").asBoolean())) continue;
            Path2D.Double ring = new Path2D.Double();
            JsonNode points = zone.path("polygon");
            if (!"mm".equals(zone.path("reviewUnit").asText()) || !points.isArray() || points.size() < 3) throw new BusinessException("已确认排版缺少有效毫米空间");
            for (int i = 0; i < points.size(); i++) {
                JsonNode p = points.get(i);
                if (!p.isArray() || p.size() != 2 || !p.get(0).isNumber() || !p.get(1).isNumber() || !Double.isFinite(p.get(0).asDouble()) || !Double.isFinite(p.get(1).asDouble())) throw new BusinessException("空间坐标无效");
                if (i == 0) ring.moveTo(p.get(0).asDouble(), p.get(1).asDouble()); else ring.lineTo(p.get(0).asDouble(), p.get(1).asDouble());
            }
            ring.closePath();
            Area room = new Area(ring);
            List<Rectangle2D.Double> bounds = furniture.stream().map(OfficeLayoutValidationPolicy::footprint).toList();
            List<Area> exclusions = new ArrayList<>();
            JsonNode preferences = zone.path("layoutPreferences");
            for (JsonNode area : preferences.path("exclusions")) exclusions.add(new Area(rectangle(area)));
            for (JsonNode obstacle : zone.path("obstacles")) {
                if (!zone.path("id").asText().equals(obstacle.path("spaceId").asText())) throw new BusinessException("建筑约束归属无效");
                // 窗为标注型约束（GEOM-015）：仍校验几何有效性，但不参与排布避让
                Area obstacleArea = obstacleArea(obstacle);
                if (!"WINDOW".equals(obstacle.path("kind").asText())) exclusions.add(obstacleArea);
            }
            // 通道禁放带：语义对齐前端 channelRects（宽度+2×净距、包围盒裁剪、「仅从二排起」按 firstBlockDepthMm 豁免首排）。
            List<Rectangle2D.Double> channelBands = channelBands(points, preferences, furniture);
            for (int i = 0; i < furniture.size(); i++) {
                JsonNode item = furniture.get(i);
                if (!item.path("layoutConfirmed").asBoolean()) continue;
                Area outside = new Area(bounds.get(i)); outside.subtract(room);
                if (!outside.isEmpty()) throw new BusinessException("已确认家具超出空间轮廓");
                // 座椅侧净空与椅子活动区仅作前端复核提示，不阻止确认保存。
                for (Area area : exclusions) if (overlaps(bounds.get(i), area)) throw new BusinessException("已确认家具占用了入口或禁放区");
                for (Rectangle2D.Double band : channelBands) if (bounds.get(i).intersects(band)) throw new BusinessException("已确认家具压占通道，请调整家具或通道位置");
                for (int j = 0; j < furniture.size(); j++) if (i != j && bounds.get(i).intersects(bounds.get(j))) throw new BusinessException("已确认家具存在重叠");
            }
            int target = preferences.path("targetSeats").asInt();
            if (preferences.has("targetSeats") && (!preferences.path("targetSeats").isIntegralNumber() || !preferences.path("targetSeats").canConvertToInt() || target < 0 || target > 500)) throw new BusinessException("目标人数无效");
            if (target > 0 && furniture.stream().allMatch(item -> item.path("layoutConfirmed").asBoolean())) {
                JsonNode primary = furniture.stream().filter(item -> "primary".equals(item.path("layoutRole").asText())).findFirst().orElse(furniture.get(0));
                String context = primary.path("layoutSettings").path("layoutContext").asText();
                boolean meeting = "meeting".equals(context) || primary.path("furnitureTemplateId").asText().startsWith("table-conference-") || primary.path("furnitureTemplateId").asText().startsWith("table-meeting-");
                long seats = furniture.stream().filter(item -> "other".equals(context) ? primary.path("furnitureTemplateId").asText().equals(item.path("furnitureTemplateId").asText()) : meeting ? item.path("furnitureTemplateId").asText().startsWith("chair-") || "chair".equals(item.path("moduleSnapshot").path("kind").asText()) : item.path("furnitureTemplateId").asText().startsWith("desk-") || "职员桌".equals(item.path("moduleSnapshot").path("category").asText())).count();
                if (seats < target) throw new BusinessException("当前排版座位不足，请调整方案或目标人数");
            }
        }
    }

    private static double number(JsonNode node, String key) {
        if (!node.path(key).isNumber() || !Double.isFinite(node.path(key).asDouble())) throw new BusinessException("家具或预留区域尺寸无效");
        return node.path(key).asDouble();
    }
    private static double nonNegative(JsonNode node, String key) {
        if (!node.has(key)) return 0;
        double value = number(node, key);
        if (value < 0) throw new BusinessException("预留间距不能为负数");
        return value;
    }
    private static Rectangle2D.Double rectangle(JsonNode node) {
        double x = number(node, "x"), y = number(node, "y"), w = number(node, "w"), h = number(node, "h");
        if (w <= 0 || h <= 0) throw new BusinessException("家具或预留区域尺寸无效");
        return new Rectangle2D.Double(x, y, w, h);
    }
    private static boolean overlaps(Rectangle2D.Double rectangle, Area exclusion) {
        Area intersection = new Area(rectangle); intersection.intersect(exclusion);
        return !intersection.isEmpty();
    }
    /** Mirrors frontend channelRects: keep-out band = widthMm + 2×clearanceMm, clipped to the polygon bounding box; startMm/endMm bound the travel-axis span (default full); fromSecondRow vertical channels start below the saved first-block depth. */
    private static List<Rectangle2D.Double> channelBands(JsonNode points, JsonNode preferences, List<JsonNode> furniture) {
        JsonNode channels = preferences.path("channels");
        if (!channels.isArray() || channels.isEmpty()) return List.of();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (JsonNode p : points) {
            minX = Math.min(minX, p.get(0).asDouble()); maxX = Math.max(maxX, p.get(0).asDouble());
            minY = Math.min(minY, p.get(1).asDouble()); maxY = Math.max(maxY, p.get(1).asDouble());
        }
        Double firstBlockDepth = null;
        for (JsonNode item : furniture) {
            JsonNode value = item.path("layoutSettings").path("firstBlockDepthMm");
            if (value.isNumber() && Double.isFinite(value.asDouble())) { firstBlockDepth = value.asDouble(); break; }
        }
        List<Rectangle2D.Double> bands = new ArrayList<>();
        for (JsonNode channel : channels) {
            JsonNode offsetNode = channel.path("offsetMm"), widthNode = channel.path("widthMm");
            if (!offsetNode.isNumber() || !widthNode.isNumber()) continue;
            double offset = offsetNode.asDouble(), width = widthNode.asDouble();
            if (!Double.isFinite(offset) || !Double.isFinite(width) || width < 1) continue;
            JsonNode clearanceNode = channel.path("clearanceMm");
            double clearance = clearanceNode.isNumber() && Double.isFinite(clearanceNode.asDouble()) && clearanceNode.asDouble() > 0 ? clearanceNode.asDouble() : 0;
            JsonNode startNode = channel.path("startMm"), endNode = channel.path("endMm");
            Double start = startNode.isNumber() && Double.isFinite(startNode.asDouble()) ? startNode.asDouble() : null;
            Double end = endNode.isNumber() && Double.isFinite(endNode.asDouble()) ? endNode.asDouble() : null;
            if ("vertical".equals(channel.path("orientation").asText())) {
                double axisFrom = start != null ? Math.max(minY, start) : minY, axisTo = end != null ? Math.min(maxY, end) : maxY;
                if (channel.path("fromSecondRow").asBoolean() && firstBlockDepth != null && firstBlockDepth > 0 && firstBlockDepth < maxY - minY) axisFrom = Math.max(axisFrom, minY + firstBlockDepth);
                double from = Math.max(minX, offset - clearance), to = Math.min(maxX, offset + width + clearance);
                if (to > from && axisTo > axisFrom) bands.add(new Rectangle2D.Double(from, axisFrom, to - from, axisTo - axisFrom));
            } else {
                double axisFrom = start != null ? Math.max(minX, start) : minX, axisTo = end != null ? Math.min(maxX, end) : maxX;
                double from = Math.max(minY, offset - clearance), to = Math.min(maxY, offset + width + clearance);
                if (to > from && axisTo > axisFrom) bands.add(new Rectangle2D.Double(axisFrom, from, axisTo - axisFrom, to - from));
            }
        }
        return bands;
    }
    private static Area obstacleArea(JsonNode obstacle) {
        JsonNode outer = obstacle.path("geometry").path("outer");
        if (!"WORLD_MM".equals(obstacle.path("geometry").path("coordinateSystem").asText()) || !outer.isArray() || outer.size() < 3) throw new BusinessException("建筑约束需要有效毫米轮廓");
        Path2D.Double path = new Path2D.Double();
        for (int index = 0; index < outer.size(); index++) {
            JsonNode point = outer.get(index);
            if (!point.isArray() || point.size() != 2 || !point.get(0).isNumber() || !point.get(1).isNumber()) throw new BusinessException("建筑约束坐标无效");
            double x = point.get(0).asDouble(), y = -point.get(1).asDouble();
            if (!Double.isFinite(x) || !Double.isFinite(y)) throw new BusinessException("建筑约束坐标无效");
            if (index == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.closePath();
        if (path.getBounds2D().getWidth() <= 0 || path.getBounds2D().getHeight() <= 0) throw new BusinessException("建筑约束尺寸无效");
        return new Area(path);
    }
    private static Rectangle2D.Double footprint(JsonNode item) {
        Rectangle2D.Double rect = rectangle(item);
        double angle = Math.toRadians(number(item, "rotation"));
        double w = Math.abs(rect.width * Math.cos(angle)) + Math.abs(rect.height * Math.sin(angle));
        double h = Math.abs(rect.width * Math.sin(angle)) + Math.abs(rect.height * Math.cos(angle));
        // Snap right-angle arithmetic so touching edges remain non-overlapping.
        w = Math.rint(w * 1000) / 1000; h = Math.rint(h * 1000) / 1000;
        return new Rectangle2D.Double(rect.x + (rect.width - w) / 2, rect.y + (rect.height - h) / 2, w, h);
    }
}
