package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Validate confirmed furniture independently of client-side button states. Drafts remain editable. */
public final class OfficeLayoutValidationPolicy {

    /**
     * 前端的 l-desk / rounded-l-desk 及其模板 ID，均按「主台面 + 侧翼」两块判定。
     * 必须与 frontend/app/utils/manualFurniture.ts 中 kind 为 l-desk / rounded-l-desk 的模板保持一致，
     * 否则前端可放、后端仍按整块矩形拒绝（例如漏掉 counter-l 时的 L 形前台）。
     */
    private static final Set<String> L_DESK_KINDS = Set.of(
        "l-desk", "rounded-l-desk", "desk-l", "desk-l-rounded", "desk-screen-l", "desk-manager-l", "desk-executive-l",
        "counter-l");
    /** 前端「单桌」变体的模板 ID 后缀。 */
    private static final String DESK_ONLY_SUFFIX = "-desk-only";

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
            // GEOM-016：有启用布置区时，已确认家具必须完整落在「启用区并集 ∩ 空间」内（与前端 allowedPlacementAreas 等价，多边形而非包围盒）。
            boolean hasLayoutRegions = hasEnabledLayoutRegions(zone);
            Area placementArea = hasLayoutRegions ? allowedPlacementArea(zone, room) : room;
            List<List<Rectangle2D.Double>> bounds = furniture.stream().map(OfficeLayoutValidationPolicy::footprints).toList();
            var meetingShapes = java.util.stream.IntStream.range(0, furniture.size()).mapToObj(i -> OfficeMeetingFootprint.parts(furniture.get(i), bounds.get(i))).toList();
            var meetingGeometry = furniture.stream().map(OfficeMeetingFootprint::usesMeetingGeometry).toList();
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
            JsonNode mixedSpacing = preferences.path("mixed").path("spacing");
            double mixedWall = nonNegative(mixedSpacing, "wallMm");
            double mixedGap = nonNegative(mixedSpacing, "gapMm");
            double mixedAisle = nonNegative(mixedSpacing, "aisleMm");
            for (int i = 0; i < furniture.size(); i++) {
                JsonNode item = furniture.get(i);
                if (!item.path("layoutConfirmed").asBoolean()) continue;
                if ("table-conference-racetrack".equals(OfficeMeetingFootprint.glyphId(item)) && item.path("w").asDouble() <= item.path("h").asDouble()) {
                    throw new BusinessException("跑道形环桌的宽度必须大于进深");
                }
                // L 型桌按主台面 + 侧翼分块判定：内角不属于家具占地，可放椅子。
                List<Rectangle2D.Double> blocks = bounds.get(i);
                if (outsidePlacement(blocks, placementArea)) throw new BusinessException(hasLayoutRegions ? "已确认家具超出已启用的家具布置区" : "已确认家具超出空间轮廓");
                // 座椅侧净空与椅子活动区仅作前端复核提示，不阻止确认保存。
                for (Area area : exclusions) if (blocksIntersectArea(blocks, area)) throw new BusinessException("已确认家具占用了入口或禁放区");
                for (Rectangle2D.Double band : channelBands) if (blocksIntersect(blocks, band)) throw new BusinessException("已确认家具压占通道，请调整家具或通道位置");
                for (int j = 0; j < furniture.size(); j++) {
                    if (i != j && blocksOverlap(blocks, bounds.get(j))
                        && (!(meetingGeometry.get(i) || meetingGeometry.get(j)) || OfficeMeetingFootprint.intersects(meetingShapes.get(i), meetingShapes.get(j)))) {
                        throw new BusinessException("已确认家具存在重叠");
                    }
                }
                String bundle = item.path("mixedBundleId").asText();
                if (mixedSpacing.isObject() && !bundle.isBlank()) {
                    for (Rectangle2D.Double block : blocks) {
                        var expanded = new Rectangle2D.Double(block.x - mixedWall, block.y - mixedWall, block.width + 2 * mixedWall, block.height + 2 * mixedWall);
                        Area beyondWall = new Area(expanded);
                        beyondWall.subtract(room);
                        if (!beyondWall.isEmpty()) throw new BusinessException("已确认组合家具离墙净距不足");
                    }
                    for (int j = 0; j < furniture.size(); j++) {
                        String other = furniture.get(j).path("mixedBundleId").asText();
                        if (i != j && !bundle.equals(other)
                            && blocksWithinGap(blocks, bounds.get(j), other.isBlank() ? mixedGap : mixedAisle)) throw new BusinessException("已确认组合家具的净距不足");
                    }
                }
            }
            int target = preferences.path("targetSeats").asInt();
            if (preferences.has("targetSeats") && (!preferences.path("targetSeats").isIntegralNumber() || !preferences.path("targetSeats").canConvertToInt() || target < 0 || target > 500)) throw new BusinessException("目标人数无效");
            if (mixedSpacing.isObject() && furniture.stream().allMatch(item -> item.path("layoutConfirmed").asBoolean())) {
                String objective = preferences.path("objective").asText("target-seats");
                JsonNode quantity = preferences.path("fixedQuantity");
                if ("fixed-quantity".equals(objective)
                    && (!quantity.isIntegralNumber() || !quantity.canConvertToInt() || quantity.asInt() < 0 || quantity.asInt() > 500)) {
                    throw new BusinessException("组合目标套数无效");
                }
                boolean fixed = "fixed-quantity".equals(objective);
                long seats = furniture.stream().filter(item -> (!fixed || !item.path("mixedBundleId").asText().isBlank())
                    && !"seat".equals(item.path("layoutRole").asText())
                    && (item.path("furnitureTemplateId").asText().startsWith("desk-") || "职员桌".equals(item.path("moduleSnapshot").path("category").asText()))).count();
                if (!"max-capacity".equals(objective) && seats < (fixed ? quantity.asInt() : target)) throw new BusinessException("当前组合的办公工位或套数不足");
            } else if (target > 0 && furniture.stream().allMatch(item -> item.path("layoutConfirmed").asBoolean())) {
                JsonNode primary = furniture.stream().filter(item -> "primary".equals(item.path("layoutRole").asText())).findFirst().orElse(furniture.get(0));
                String context = primary.path("layoutSettings").path("layoutContext").asText();
                boolean meeting = "meeting".equals(context) || primary.path("furnitureTemplateId").asText().startsWith("table-conference-") || primary.path("furnitureTemplateId").asText().startsWith("table-meeting-");
                long seats = meeting && !"other".equals(context) ? furniture.stream().mapToInt(OfficeLayoutValidationPolicy::meetingSeats).sum()
                    : furniture.stream().filter(item -> "other".equals(context) ? primary.path("furnitureTemplateId").asText().equals(item.path("furnitureTemplateId").asText())
                        : item.path("furnitureTemplateId").asText().startsWith("desk-") || "职员桌".equals(item.path("moduleSnapshot").path("category").asText())).count();
                if (seats < target) throw new BusinessException("当前排版座位不足，请调整方案或目标人数");
            }
        }
    }

    private static int meetingSeats(JsonNode item) {
        String id = item.path("furnitureTemplateId").asText();
        return switch (id) {
            case "sofa-meeting-double" -> 2;
            case "sofa-meeting-single" -> 1;
            case "chair-meeting-observer" -> 0;
            default -> id.startsWith("chair-") || "chair".equals(item.path("moduleSnapshot").path("kind").asText()) ? 1 : 0;
        };
    }

    private static boolean blocksWithinGap(List<Rectangle2D.Double> first, List<Rectangle2D.Double> second, double gap) {
        double epsilon = 0.001;
        for (Rectangle2D.Double a : first) for (Rectangle2D.Double b : second) {
            if (a.x < b.getMaxX() + gap - epsilon && b.x < a.getMaxX() + gap - epsilon
                && a.y < b.getMaxY() + gap - epsilon && b.y < a.getMaxY() + gap - epsilon) return true;
        }
        return false;
    }

    private static boolean outsidePlacement(List<Rectangle2D.Double> blocks, Area placement) {
        for (Rectangle2D.Double block : blocks) {
            Area outside = new Area(block); outside.subtract(placement);
            if (!outside.isEmpty()) return true;
        }
        return false;
    }

    private static boolean blocksIntersectArea(List<Rectangle2D.Double> blocks, Area area) {
        for (Rectangle2D.Double block : blocks) if (overlaps(block, area)) return true;
        return false;
    }

    private static boolean blocksIntersect(List<Rectangle2D.Double> blocks, Rectangle2D.Double rect) {
        for (Rectangle2D.Double block : blocks) if (block.intersects(rect)) return true;
        return false;
    }

    private static boolean blocksOverlap(List<Rectangle2D.Double> first, List<Rectangle2D.Double> second) {
        for (Rectangle2D.Double a : first) for (Rectangle2D.Double b : second) if (a.intersects(b)) return true;
        return false;
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
    /**
     * GEOM-016 布置区（与前端 layoutRegions.ts 等价）：zone.layoutRegions 中 enabled=true、归属本空间且
     * 多边形有效（≥3 点、坐标有限）的区域参与计算；polygonMm 为世界毫米 Y 向上，取负转到画布坐标与 room 一致。
     * 畸形/越界区域跳过——绘制端由前端拒绝落库，服务端只对有效区做排版对称校验，不因标注数据拒绝整稿保存。
     */
    private static boolean hasEnabledLayoutRegions(JsonNode zone) {
        for (JsonNode region : zone.path("layoutRegions")) {
            if (region.path("enabled").asBoolean() && zone.path("id").asText().equals(region.path("spaceId").asText()) && regionArea(region) != null) return true;
        }
        return false;
    }
    private static Area allowedPlacementArea(JsonNode zone, Area room) {
        Area union = null;
        for (JsonNode region : zone.path("layoutRegions")) {
            if (!region.path("enabled").asBoolean() || !zone.path("id").asText().equals(region.path("spaceId").asText())) continue;
            Area area = regionArea(region);
            if (area == null) continue;
            if (union == null) union = area; else union.add(area);
        }
        if (union == null) return room;
        union.intersect(room);
        return union;
    }
    /** 区域多边形 → Area（y 取负到画布坐标）；无效返回 null（跳过而非拒存）。 */
    private static Area regionArea(JsonNode region) {
        JsonNode polygon = region.path("polygonMm");
        if (!polygon.isArray() || polygon.size() < 3) return null;
        Path2D.Double path = new Path2D.Double();
        for (int index = 0; index < polygon.size(); index++) {
            JsonNode point = polygon.get(index);
            if (!point.isArray() || point.size() != 2 || !point.get(0).isNumber() || !point.get(1).isNumber()) return null;
            double x = point.get(0).asDouble(), y = -point.get(1).asDouble();
            if (!Double.isFinite(x) || !Double.isFinite(y)) return null;
            if (index == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.closePath();
        if (path.getBounds2D().getWidth() <= 0 || path.getBounds2D().getHeight() <= 0) return null;
        return new Area(path);
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
    /** 与前端一致：L 型桌拆成主台面 + 侧翼，其余家具是整块旋转外接矩形。 */
    private static List<Rectangle2D.Double> footprints(JsonNode item) {
        Rectangle2D.Double rect = rectangle(item);
        double angle = Math.toRadians(number(item, "rotation"));
        boolean lDesk = L_DESK_KINDS.contains(furnitureKind(item));
        double wing = Math.min(item.path("wingWidthMm").isNumber() ? item.path("wingWidthMm").asDouble() : 550,
            Math.min(rect.width / 2, rect.height / 2));
        String templateId = item.path("furnitureTemplateId").asText("");
        double desktopDepth = Math.min(rect.height * 0.48, templateId.startsWith("desk-executive") ? 800 : 650);
        if (!lDesk || wing <= 0 || desktopDepth <= 0 || desktopDepth >= rect.height) {
            return List.of(rotatedBlock(rect, angle, 0, 0, rect.width, rect.height));
        }
        boolean mirrorX = item.path("mirrorX").asBoolean(false);
        double wingX = mirrorX ? 0 : rect.width - wing;
        return List.of(
            rotatedBlock(rect, angle, 0, 0, rect.width, desktopDepth),
            rotatedBlock(rect, angle, wingX, desktopDepth, wing, rect.height - desktopDepth));
    }

    private static String furnitureKind(JsonNode item) {
        String snapshotKind = item.path("moduleSnapshot").path("kind").asText("");
        if (!snapshotKind.isBlank()) return snapshotKind;
        // 前端目录会把带座椅的模板再生成一个「单桌」变体（id 追加 -desk-only，kind 沿用原模板），
        // 这里先还原基础模板 ID，否则 desk-l-desk-only 之类会被当成普通整块矩形。
        String templateId = item.path("furnitureTemplateId").asText("");
        String baseId = templateId.endsWith(DESK_ONLY_SUFFIX)
            ? templateId.substring(0, templateId.length() - DESK_ONLY_SUFFIX.length()) : templateId;
        return L_DESK_KINDS.contains(baseId) ? "l-desk" : "";
    }

    private static Rectangle2D.Double rotatedBlock(Rectangle2D.Double origin, double angle,
                                                   double x, double y, double w, double h) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        double cx = origin.width / 2, cy = origin.height / 2;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double[][] corners = {{x, y}, {x + w, y}, {x + w, y + h}, {x, y + h}};
        for (double[] corner : corners) {
            double dx = corner[0] - cx, dy = corner[1] - cy;
            double px = cx + dx * cos - dy * sin;
            double py = cy + dx * sin + dy * cos;
            minX = Math.min(minX, px); maxX = Math.max(maxX, px);
            minY = Math.min(minY, py); maxY = Math.max(maxY, py);
        }
        // Snap right-angle arithmetic so touching edges remain non-overlapping.
        return new Rectangle2D.Double(origin.x + snap(minX), origin.y + snap(minY),
            snap(maxX - minX), snap(maxY - minY));
    }

    private static double snap(double value) {
        return Math.rint(value * 1000) / 1000;
    }
}
