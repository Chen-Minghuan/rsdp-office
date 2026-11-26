package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Reads the saved layout's world-space footprints and reports measured spacing in millimeters. */
public final class RenderFurnitureSpacingTool {
    private static final double CONTACT_EPSILON = 0.001;
    private static final double ALIGNMENT_MM = 1;

    private RenderFurnitureSpacingTool() {}

    private record Footprint(RenderSpatialControl.FurnitureControl item, List<Line2D.Double> edges) {
        String ref() { return item.referenceId(); }
    }

    private record Neighbor(Footprint target, double gap, double centerDistance) {}
    private record Direction(String ref, String axis, boolean forward) {}
    private record Facing(Footprint from, Footprint to, String axis, double gap) {}
    private record Range(double min, double max) {}

    public static ObjectNode measure(List<RenderSpatialControl.FurnitureControl> furniture) {
        List<Footprint> footprints = furniture.stream().map(item -> new Footprint(item, edges(item.worldGroundPolygon()))).toList();
        Map<String, Neighbor> nearest = new LinkedHashMap<>();
        Map<Direction, Facing> facing = new LinkedHashMap<>();
        for (int i = 0; i < footprints.size(); i++) {
            Footprint a = footprints.get(i);
            for (int j = i + 1; j < footprints.size(); j++) {
                Footprint b = footprints.get(j);
                double gap = distance(a.edges(), b.edges());
                double center = Math.hypot(a.item().centerXmm() - b.item().centerXmm(), a.item().centerYmm() - b.item().centerYmm());
                keepClosest(nearest, a, new Neighbor(b, gap, center));
                keepClosest(nearest, b, new Neighbor(a, gap, center));
                Facing pair = facingPair(a, b, gap);
                if (pair != null) {
                    keepFacing(facing, new Direction(pair.from().ref(), pair.axis(), true), pair);
                    keepFacing(facing, new Direction(pair.to().ref(), pair.axis(), false), pair);
                }
            }
        }

        ObjectNode result = JsonNodeFactory.instance.objectNode().put("version", "furniture-spacing-v1").put("unit", "mm");
        var modules = result.putArray("modules");
        for (Footprint footprint : footprints) {
            var item = modules.addObject().put("referenceId", footprint.ref()).put("furnitureId", footprint.item().furnitureId());
            Neighbor other = nearest.get(footprint.ref());
            if (other != null) item.putObject("nearest").put("referenceId", other.target().ref())
                .put("edgeGapMm", mm(other.gap())).put("centerDistanceMm", mm(other.centerDistance()));
        }
        List<Facing> mutual = facing.values().stream().distinct().filter(pair ->
            pair.equals(facing.get(new Direction(pair.from().ref(), pair.axis(), true))) &&
                pair.equals(facing.get(new Direction(pair.to().ref(), pair.axis(), false)))).toList();
        var adjacent = result.putArray("adjacent");
        for (Facing pair : mutual) adjacent.addObject().put("from", pair.from().ref()).put("to", pair.to().ref())
            .put("axis", pair.axis()).put("edgeGapMm", mm(pair.gap())).put("touching", pair.gap() <= CONTACT_EPSILON);
        List<List<String>> rows = joinedRows(mutual);
        var joined = result.putArray("joinedRows");
        for (List<String> row : rows) {
            var refs = joined.addArray();
            row.forEach(refs::add);
        }
        result.put("description", describe(footprints.size(), rows, mutual));
        return result;
    }

    private static void keepClosest(Map<String, Neighbor> nearest, Footprint source, Neighbor candidate) {
        nearest.merge(source.ref(), candidate, (a, b) ->
            Comparator.comparingDouble(Neighbor::gap).thenComparingDouble(Neighbor::centerDistance).compare(a, b) <= 0 ? a : b);
    }

    private static void keepFacing(Map<Direction, Facing> neighbors, Direction direction, Facing candidate) {
        neighbors.merge(direction, candidate, (a, b) -> a.gap() <= b.gap() ? a : b);
    }

    private static List<Line2D.Double> edges(List<Point2D.Double> polygon) {
        List<Line2D.Double> result = new ArrayList<>();
        for (int i = 0; i < polygon.size(); i++) result.add(new Line2D.Double(polygon.get(i), polygon.get((i + 1) % polygon.size())));
        return result;
    }

    private static double distance(List<Line2D.Double> first, List<Line2D.Double> second) {
        double min = Double.POSITIVE_INFINITY;
        for (Line2D.Double a : first) for (Line2D.Double b : second) {
            if (segmentsIntersect(a, b)) return 0;
            min = Math.min(min, Math.min(Math.min(a.ptSegDist(b.x1, b.y1), a.ptSegDist(b.x2, b.y2)),
                Math.min(b.ptSegDist(a.x1, a.y1), b.ptSegDist(a.x2, a.y2))));
        }
        return min <= CONTACT_EPSILON ? 0 : min;
    }

    private static boolean segmentsIntersect(Line2D.Double a, Line2D.Double b) {
        // Reject disjoint bounds before the orientation test. Almost-collinear rotated edges can
        // otherwise register as intersecting when floating-point cross products change sign.
        return Math.max(Math.min(a.x1, a.x2), Math.min(b.x1, b.x2)) <= Math.min(Math.max(a.x1, a.x2), Math.max(b.x1, b.x2))
            && Math.max(Math.min(a.y1, a.y2), Math.min(b.y1, b.y2)) <= Math.min(Math.max(a.y1, a.y2), Math.max(b.y1, b.y2))
            && a.intersectsLine(b);
    }

    private static Facing facingPair(Footprint a, Footprint b, double gap) {
        double angle = Math.IEEEremainder(a.item().source().path("rotation").asDouble(), 180);
        if (Math.abs(Math.abs(angle) - 90) <= CONTACT_EPSILON) angle = 90;
        double other = b.item().source().path("rotation").asDouble();
        if (Math.abs(Math.IEEEremainder(angle - other, 180)) > CONTACT_EPSILON) return null;
        double radians = Math.toRadians(angle), ux = Math.cos(radians), uy = Math.sin(radians);
        double dx = b.item().centerXmm() - a.item().centerXmm(), dy = b.item().centerYmm() - a.item().centerYmm();
        double along = dx * ux + dy * uy, cross = -dx * uy + dy * ux;
        if (Math.abs(cross) <= ALIGNMENT_MM && Math.abs(along) > ALIGNMENT_MM)
            return face(along > 0 ? a : b, along > 0 ? b : a, ux, uy, "width", gap);
        if (Math.abs(along) <= ALIGNMENT_MM && Math.abs(cross) > ALIGNMENT_MM)
            return face(cross > 0 ? a : b, cross > 0 ? b : a, -uy, ux, "depth", gap);
        return null;
    }

    private static Facing face(Footprint from, Footprint to, double ux, double uy, String axis, double gap) {
        Range a = projection(from, ux, uy), b = projection(to, ux, uy);
        double projectedGap = b.min() - a.max();
        if (projectedGap < -CONTACT_EPSILON || Math.abs(projectedGap - gap) > CONTACT_EPSILON) return null;
        // A shared corner alone is insufficient evidence of a joined row.
        Range aFace = faceInterval(from, ux, uy, a.max()), bFace = faceInterval(to, ux, uy, b.min());
        double span = Math.min(aFace.max(), bFace.max()) - Math.max(aFace.min(), bFace.min());
        if (span <= ALIGNMENT_MM) return null;
        return new Facing(from, to, axis, gap);
    }

    private static Range faceInterval(Footprint footprint, double ux, double uy, double edge) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (Point2D.Double point : footprint.item().worldGroundPolygon()) {
            if (Math.abs(point.x * ux + point.y * uy - edge) > CONTACT_EPSILON) continue;
            double cross = -point.x * uy + point.y * ux;
            min = Math.min(min, cross);
            max = Math.max(max, cross);
        }
        return new Range(min, max);
    }

    private static Range projection(Footprint footprint, double ux, double uy) {
        var polygon = footprint.item().worldGroundPolygon();
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (Point2D.Double point : polygon) {
            double along = point.x * ux + point.y * uy;
            min = Math.min(min, along);
            max = Math.max(max, along);
        }
        return new Range(min, max);
    }

    private static List<List<String>> joinedRows(List<Facing> pairs) {
        Map<String, String> links = new LinkedHashMap<>();
        for (Facing pair : pairs) {
            if ("width".equals(pair.axis()) && pair.gap() <= CONTACT_EPSILON && pair.from().item().type().equals(pair.to().item().type()))
                links.put(pair.from().ref(), pair.to().ref());
        }
        List<List<String>> rows = new ArrayList<>();
        for (String start : links.keySet()) {
            if (links.containsValue(start)) continue;
            List<String> row = new ArrayList<>();
            for (String next = start; next != null; next = links.get(next)) row.add(next);
            rows.add(row);
        }
        return rows;
    }

    private static String describe(int count, List<List<String>> rows, List<Facing> pairs) {
        StringBuilder text = new StringBuilder();
        if (!rows.isEmpty()) {
            text.append("布局实测：");
            int size = rows.getFirst().size();
            if (rows.stream().allMatch(row -> row.size() == size) && rows.size() * size == count) {
                text.append("共").append(rows.size()).append("排，每排").append(size).append("件。");
            } else {
                text.append("有").append(rows.size()).append("组同排相贴家具。");
            }
            text.append("每组相邻模块边到边净距0 mm，侧边相接，保留单件分缝，不能拉开或合并成一件。");
            if (rows.size() * size != count || rows.stream().anyMatch(row -> row.size() != size)) {
                text.append("相贴组（最多列出12组）：").append(rows.stream().limit(12)
                    .map(row -> String.join("→", row)).collect(Collectors.joining("；"))).append("。");
            }
        }
        String depthGaps = pairs.stream().filter(pair -> "depth".equals(pair.axis()) && pair.gap() > CONTACT_EPSILON
                && pair.from().item().type().equals(pair.to().item().type()))
            .map(pair -> mm(pair.gap())).distinct().sorted().limit(6)
            .map(gap -> BigDecimal.valueOf(gap).stripTrailingZeros().toPlainString()).collect(Collectors.joining("、"));
        if (!depthGaps.isEmpty()) text.append("前后对齐的相邻同类模块边缘净距实测 ").append(depthGaps)
            .append(" mm。将留白保留在这些模块之间。");
        return text.toString();
    }

    private static double mm(double value) { return Math.round(value * 1000) / 1000.0; }
}
