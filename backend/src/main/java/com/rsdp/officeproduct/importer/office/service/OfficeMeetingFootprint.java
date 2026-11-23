package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/** The same plan-view contours and 0.001 mm contact tolerance as meetingFurnitureCollision.ts. */
final class OfficeMeetingFootprint {
    private OfficeMeetingFootprint() {}

    record Point(double x, double y) {}

    static boolean usesMeetingGeometry(JsonNode item) {
        String id = glyphId(item);
        return id.startsWith("chair-meeting-") || id.startsWith("table-meeting-") || id.startsWith("table-conference-");
    }

    static String glyphId(JsonNode item) {
        return item.path("moduleSnapshot").path("glyphTemplateId").asText(item.path("furnitureTemplateId").asText());
    }

    static List<List<Point>> parts(JsonNode item, List<Rectangle2D.Double> blocks) {
        if (blocks.size() > 1) return blocks.stream().map(block -> List.of(
            new Point(block.x, block.y), new Point(block.getMaxX(), block.y),
            new Point(block.getMaxX(), block.getMaxY()), new Point(block.x, block.getMaxY()))).toList();
        String id = glyphId(item);
        double w = item.path("w").asDouble(), h = item.path("h").asDouble();
        double x = item.path("x").asDouble(), y = item.path("y").asDouble();
        double radians = Math.toRadians(item.path("rotation").asDouble());
        double sin = Math.sin(radians), cos = Math.cos(radians);
        List<List<Point>> local;
        if ("table-meeting-video-v".equals(id)) {
            local = List.of(
                List.of(new Point(0, h * 0.162), new Point(w * 0.093, 0), new Point(w / 2, h * 0.677), new Point(w / 2, h)),
                List.of(new Point(w / 2, h), new Point(w / 2, h * 0.677), new Point(w * 0.907, 0), new Point(w, h * 0.162)));
        } else if ("table-conference-racetrack".equals(id) && w > h) {
            local = List.of(curvedOutline(h / 2, h / 2, w - h));
        } else if ("table-conference-ring".equals(id) || "table-meeting-round".equals(id) || "table-meeting-round-two".equals(id)) {
            local = List.of(curvedOutline(w / 2, h / 2, 0));
        } else {
            local = List.of(List.of(new Point(0, 0), new Point(w, 0), new Point(w, h), new Point(0, h)));
        }
        return local.stream().map(part -> part.stream().map(p -> new Point(
            x + w / 2 + (p.x - w / 2) * cos - (p.y - h / 2) * sin,
            y + h / 2 + (p.x - w / 2) * sin + (p.y - h / 2) * cos)).toList()).toList();
    }

    private static List<Point> curvedOutline(double rx, double ry, double run) {
        List<Point> points = new ArrayList<>(48);
        int start = run > 0 ? -12 : 0;
        double scale = 1 / Math.cos(Math.PI / 48);
        for (int i = start; i < start + 48; i++) {
            double angle = (i + 0.5) * Math.PI / 24;
            points.add(new Point(rx + (i < 12 ? run : 0) + rx * Math.cos(angle) * scale, ry + ry * Math.sin(angle) * scale));
        }
        return points;
    }

    static boolean intersects(List<List<Point>> a, List<List<Point>> b) {
        for (List<Point> first : a) for (List<Point> second : b) {
            if (!separated(first, second) && !separated(second, first)) return true;
        }
        return false;
    }

    private static boolean separated(List<Point> a, List<Point> b) {
        for (int i = 0; i < a.size(); i++) {
            Point p = a.get(i), q = a.get((i + 1) % a.size());
            double length = Math.hypot(q.x - p.x, q.y - p.y);
            if (length < 0.001) continue;
            double nx = (p.y - q.y) / length, ny = (q.x - p.x) / length;
            double[] pa = extent(a, nx, ny), pb = extent(b, nx, ny);
            if (Math.min(pa[1], pb[1]) <= Math.max(pa[0], pb[0]) + 0.001) return true;
        }
        return false;
    }

    private static double[] extent(List<Point> points, double nx, double ny) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (Point p : points) {
            double value = p.x * nx + p.y * ny;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return new double[] {min, max};
    }
}
