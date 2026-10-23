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
                exclusions.add(obstacleArea(obstacle));
            }
            // 通道禁放带：语义对齐前端 channelRects（宽度+2×净距、包围盒裁剪、「仅从二排起」按 firstBlockDepthMm 豁免首排）。
            List<Rectangle2D.Double> channelBands = channelBands(points, preferences, furniture);
            for (int i = 0; i < furniture.size(); i++) {
                JsonNode item = furniture.get(i);
                if (!item.path("layoutConfirmed").asBoolean()) continue;
                Area outside = new Area(bounds.get(i)); outside.subtract(room);
                if (!outside.isEmpty()) throw new BusinessException("已确认家具超出空间轮廓");
                JsonNode spacing = item.path("layoutSettings").path("spacing");
                if (item.hasNonNull("layoutGroupId")) {
                    double wall = nonNegative(spacing, "wallMm"), aisle = nonNegative(spacing, "aisleMm");
                    double margin = "seat".equals(item.path("layoutRole").asText()) ? Math.max(wall, aisle) : wall;
                    Rectangle2D.Double rect = bounds.get(i);
                    Area expanded = new Area(new Rectangle2D.Double(rect.x - margin, rect.y - margin, rect.width + margin * 2, rect.height + margin * 2));
                    expanded.subtract(room);
                    if (!expanded.isEmpty()) throw new BusinessException("已确认家具离墙或通行预留不足");
                }
                for (Area area : exclusions) if (overlaps(bounds.get(i), area)) throw new BusinessException("已确认家具占用了入口或禁放区");
                for (Rectangle2D.Double band : channelBands) if (bounds.get(i).intersects(band)) throw new BusinessException("已确认家具压占通道，请调整家具或通道位置");
                for (int j = 0; j < furniture.size(); j++) if (i != j && bounds.get(i).intersects(bounds.get(j))) throw new BusinessException("已确认家具存在重叠");
                double clearance = nonNegative(preferences, "seatClearanceMm");
                if ("seat".equals(item.path("layoutRole").asText()) && clearance > 0) {
                    Rectangle2D.Double activity = chairActivity(item, clearance);
                    Area activityOutside = new Area(activity); activityOutside.subtract(room);
                    if (!activityOutside.isEmpty()) throw new BusinessException("椅子活动区超出空间");
                    for (Area area : exclusions) if (overlaps(activity, area)) throw new BusinessException("椅子活动区占用禁放区");
                    for (int j = 0; j < furniture.size(); j++) if (i != j && activity.intersects(bounds.get(j))) throw new BusinessException("椅子活动区被家具占用");
                }
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
    private static Rectangle2D.Double chairActivity(JsonNode item, double clearance) {
        double radians = Math.toRadians(number(item, "rotation")), width = number(item, "w"), depth = number(item, "h");
        double offset = -depth / 2 - clearance / 2;
        double cx = number(item, "x") + width / 2 - Math.sin(radians) * offset;
        double cy = number(item, "y") + depth / 2 + Math.cos(radians) * offset;
        double w = Math.rint((Math.abs(width * Math.cos(radians)) + Math.abs(clearance * Math.sin(radians))) * 1000) / 1000;
        double h = Math.rint((Math.abs(width * Math.sin(radians)) + Math.abs(clearance * Math.cos(radians))) * 1000) / 1000;
        return new Rectangle2D.Double(Math.rint((cx - w / 2) * 1000) / 1000, Math.rint((cy - h / 2) * 1000) / 1000, w, h);
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
    /** Mirrors frontend channelRects: keep-out band = widthMm + 2×clearanceMm, clipped to the polygon bounding box; fromSecondRow vertical channels start below the saved first-block depth. */
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
            if ("vertical".equals(channel.path("orientation").asText())) {
                double y = minY, h = maxY - minY;
                if (channel.path("fromSecondRow").asBoolean() && firstBlockDepth != null && firstBlockDepth > 0 && firstBlockDepth < h) { y = minY + firstBlockDepth; h = maxY - y; }
                double from = Math.max(minX, offset - clearance), to = Math.min(maxX, offset + width + clearance);
                if (to > from) bands.add(new Rectangle2D.Double(from, y, to - from, h));
            } else {
                double from = Math.max(minY, offset - clearance), to = Math.min(maxY, offset + width + clearance);
                if (to > from) bands.add(new Rectangle2D.Double(minX, from, maxX - minX, to - from));
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
