package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.dto.spatial.SpatialGeometry;
import com.rsdp.officeproduct.importer.service.spatial.SpatialGeometryService;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.*;

/** Conservative, axis-aligned raster wall grounding; never a replacement for CAD parsing or scale calibration. */
final class RasterWallContours {
    private static final Set<String> ENCLOSED = Set.of("EXECUTIVE_OFFICE", "MEETING_ROOM", "KITCHEN", "BATHROOM", "STUDY");
    private record Region(int id, int size, List<List<Double>> ring) {}

    static ObjectNode refine(JsonNode initial, VisionImagePreparer.PreparedImage page, ObjectMapper mapper, SpatialGeometryService geometry) {
        return refine(initial, page, mapper, geometry, mapper.createObjectNode());
    }

    static ObjectNode refine(JsonNode initial, VisionImagePreparer.PreparedImage page, ObjectMapper mapper, SpatialGeometryService geometry, JsonNode localization) {
        ObjectNode result = initial.deepCopy();
        if (!initial.path("rooms").isArray()) return result;
        ArrayNode warnings = mapper.createArrayNode();
        if (initial.path("warnings").isArray()) warnings.addAll((ArrayNode) initial.path("warnings"));
        result.set("warnings", warnings);
        ObjectNode evidence = result.putObject("contourRefinement");
        evidence.put("method", "RASTER_WALL_REGION_V4");
        Map<Integer, List<Double>> anchors = roomAnchors(initial.path("rooms"), localization);
        boolean located = "DETECTED".equals(localization.path("status").asText());
        evidence.put("seedSource", located ? "INDEPENDENT_LABEL_DETECTION" : "MODEL_POLYGON_CENTRE");
        if ("UNAVAILABLE".equals(localization.path("status").asText())) warnings.add("房名独立定位未完成，墙线校正仍为初次候选位置，请复核");
        ArrayNode records = evidence.putArray("rooms");
        int accepted = 0, attempted = 0;
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(page.png()));
            double factor = Math.min(1, 2048.0 / Math.max(page.width(), page.height()));
            BufferedImage image = new BufferedImage(Math.max(1, (int) Math.round(page.width() * factor)), Math.max(1, (int) Math.round(page.height() * factor)), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try { graphics.drawImage(original, 0, 0, image.getWidth(), image.getHeight(), null); } finally { graphics.dispose(); }
            int width = image.getWidth(), height = image.getHeight();
            evidence.put("analysisWidth", width).put("analysisHeight", height).put("estimatedDoorClosures", true).put("requiresReview", true);
            boolean[] walls = wallMask(image);
            int[] regions = new int[width * height], queue = new int[width * height];
            Map<Integer, Region> cache = new HashMap<>();
            Map<Integer, Integer> assignments = new HashMap<>();
            Map<Integer, Region> proposals = new HashMap<>();
            int nextId = 1;
            for (int index = 0; index < initial.path("rooms").size(); index++) {
                JsonNode room = initial.path("rooms").get(index);
                if (!eligible(room, index, located, anchors)) continue;
                attempted++;
                List<List<Double>> candidate = points(room.path("polygon"));
                if (candidate == null) continue;
                var anchor = anchors.get(index);
                double cx = (anchor == null ? candidate.stream().mapToDouble(p -> p.get(0)).average().orElse(0) : anchor.get(0)) / 1000 * width;
                double cy = (anchor == null ? candidate.stream().mapToDouble(p -> p.get(1)).average().orElse(0) : anchor.get(1)) / 1000 * height;
                int x = (int) Math.round(cx), y = (int) Math.round(cy);
                if (x < 0 || x >= width || y < 0 || y >= height || walls[y * width + x]) continue;
                int cell = y * width + x, id = regions[cell];
                Region region;
                if (id == 0) { id = nextId++; region = flood(cell, id, walls, regions, queue, width, height); cache.put(id, region); }
                else region = cache.get(id);
                if (region == null || region.ring() == null) continue;
                proposals.put(index, region);
                assignments.merge(id, 1, Integer::sum);
            }
            for (int index = 0; index < initial.path("rooms").size(); index++) {
                JsonNode room = initial.path("rooms").get(index);
                if (!eligible(room, index, located, anchors)) continue;
                Region region = proposals.get(index);
                ObjectNode record = records.addObject().put("candidateIndex", index).put("label", room.path("label").asText());
                var anchor = anchors.get(index);
                if (located) {
                    record.set("labelAnchor", mapper.valueToTree(anchor));
                    ((ObjectNode) result.path("rooms").get(index)).set("labelAnchor", mapper.valueToTree(anchor));
                }
                List<List<Double>> global = region == null ? null : region.ring().stream()
                    .map(p -> List.of(p.get(0) / width * 1000, p.get(1) / height * 1000)).toList();
                if (global == null || assignments.get(region.id()) != 1 || !valid(global, geometry)
                    || !(located ? ownsLabel(initial.path("rooms"), index, global, anchors) : sameRoom(initial.path("rooms"), index, global))) {
                    record.put("status", "UNRESOLVED").put("reason", region == null ? "NO_CLOSED_WALL_REGION" : assignments.get(region.id()) != 1 ? "AMBIGUOUS_REGION" : "GEOMETRY_OR_OWNERSHIP_CONFLICT");
                    ((ObjectNode) result.path("rooms").get(index)).put("confidence", "low");
                    warnings.add(room.path("label").asText() + "：墙线未可靠闭合或归属有歧义，保留识别候选，请复核");
                    var candidate = points(room.path("polygon"));
                    if (located && candidate != null && !shape(candidate).contains(anchor.get(0), anchor.get(1)))
                        warnings.add(room.path("label").asText() + "：房名定位与候选轮廓不符，该轮廓不能作为准确识别结果");
                    continue;
                }
                ((ObjectNode) result.path("rooms").get(index)).set("polygon", mapper.valueToTree(global));
                ((ObjectNode) result.path("rooms").get(index)).put("contourGrounded", located);
                record.put("status", "GROUNDED").put("regionPixels", region.size());
                accepted++;
            }
            if (accepted > 0) warnings.add("墙线核查含门洞估计补线，空间轮廓仍需按图复核");
            evidence.put("status", accepted == 0 ? "UNRESOLVED" : accepted == attempted ? "APPLIED" : "PARTIAL");
        } catch (java.io.IOException | IllegalArgumentException failure) {
            warnings.add("图像墙线核查未完成，保留初次识别候选，请复核");
            evidence.put("status", "FAILED");
        }
        evidence.put("attemptedCount", attempted).put("acceptedCount", accepted);
        return result;
    }

    private static String labelKey(String text) { return text.replaceAll("\\s+", ""); }

    /** Match repeated text by unique containment, never by list order or a guessed name suffix. */
    private static Map<Integer, List<Double>> roomAnchors(JsonNode rooms, JsonNode localization) {
        Map<String, List<List<Double>>> labels = new HashMap<>();
        for (var label : localization.path("labels")) {
            String name = labelKey(label.path("text").asText());
            var box = label.path("bbox");
            if (name.isEmpty() || !box.isArray() || box.size() != 4) continue;
            boolean valid = true;
            for (var v : box) if (!v.isNumber() || !Double.isFinite(v.asDouble()) || v.asDouble() < 0 || v.asDouble() > 1000) valid = false;
            double left = box.get(0).asDouble(), top = box.get(1).asDouble(), right = box.get(2).asDouble(), bottom = box.get(3).asDouble();
            if (!valid || right <= left || bottom <= top || right - left > 200 || bottom - top > 100) continue;
            var center = List.of((left + right) / 2, (top + bottom) / 2);
            var group = labels.computeIfAbsent(name, ignored -> new ArrayList<>());
            if (group.stream().noneMatch(p -> Math.hypot(p.get(0) - center.get(0), p.get(1) - center.get(1)) < 2)) group.add(center);
        }
        Map<Integer, List<Double>> proposals = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        for (var room : rooms) counts.merge(labelKey(room.path("label").asText()), 1, Integer::sum);
        for (int i = 0; i < rooms.size(); i++) {
            var room = rooms.get(i);
            String name = labelKey(room.path("label").asText());
            var group = labels.getOrDefault(name, List.of());
            if (group.size() == 1 && counts.get(name) == 1) proposals.put(i, group.get(0));
            else if (group.size() == counts.get(name)) {
                var candidate = points(room.path("polygon"));
                if (candidate == null) continue;
                var region = shape(candidate);
                var bounds = region.getBounds2D();
                var contained = group.stream().filter(p -> region.intersects(p.get(0) - 3, p.get(1) - 3, 6, 6))
                    .sorted(Comparator.comparingDouble(p -> Math.hypot(p.get(0) - bounds.getCenterX(), p.get(1) - bounds.getCenterY()))).toList();
                if (!contained.isEmpty() && (contained.size() == 1
                    || Math.hypot(contained.get(0).get(0) - bounds.getCenterX(), contained.get(0).get(1) - bounds.getCenterY())
                    < .75 * Math.hypot(contained.get(1).get(0) - bounds.getCenterX(), contained.get(1).get(1) - bounds.getCenterY()))) proposals.put(i, contained.get(0));
            }
        }
        Map<Integer, List<Double>> result = new HashMap<>();
        for (var entry : proposals.entrySet()) {
            String name = labelKey(rooms.get(entry.getKey()).path("label").asText());
            long uses = proposals.entrySet().stream().filter(other -> name.equals(labelKey(rooms.get(other.getKey()).path("label").asText())) && other.getValue().equals(entry.getValue())).count();
            if (uses == 1) result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static boolean eligible(JsonNode room, int index, boolean located, Map<Integer, List<Double>> anchors) {
        return room.path("label").isTextual() && !Set.of("OPEN_OFFICE", "HALLWAY", "COLLABORATION").contains(room.path("roomType").asText()) && (located ? anchors.containsKey(index) : ENCLOSED.contains(room.path("roomType").asText()));
    }

    private static boolean ownsLabel(JsonNode rooms, int index, List<List<Double>> ring, Map<Integer, List<Double>> anchors) {
        var region = shape(ring);
        var anchor = anchors.get(index);
        if (anchor == null || !region.contains(anchor.get(0), anchor.get(1))) return false;
        for (var entry : anchors.entrySet())
            if (entry.getKey() != index && region.contains(entry.getValue().get(0), entry.getValue().get(1))) return false;
        return true;
    }

    private static boolean structuralPixel(int rgb, int threshold) {
        int r = rgb >> 16 & 255, g = rgb >> 8 & 255, b = rgb & 255;
        // Orange room labels and coloured furniture are not structural wall strokes.
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 50
            && r * 299 + g * 587 + b * 114 < threshold * 1000;
    }

    private static boolean[] wallMask(BufferedImage image) {
        int width = image.getWidth(), height = image.getHeight();
        boolean[] dark = new boolean[width * height], wall = new boolean[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int rgb = image.getRGB(x, y);
            dark[y * width + x] = structuralPixel(rgb, 185);
        }
        int span = Math.max(24, (int) Math.round(Math.max(width, height) * .022));
        // Substantial wall strokes: a long run must have three-pixel thickness, excluding ordinary furniture lines.
        for (boolean horizontal : new boolean[]{true, false}) {
            int alongSize = horizontal ? width : height, crossSize = horizontal ? height : width;
            for (int cross = 2; cross < crossSize - 2; cross++) {
                int start = -1, last = -1;
                for (int along = 0; along < alongSize + 3; along++) {
                    boolean solid = along < alongSize;
                    for (int d = -1; d <= 1 && solid; d++) solid = dark[index(horizontal, along, cross + d, width)];
                    if (solid) { if (start < 0) start = along; last = along; }
                    else if (start >= 0 && along - last > 2) {
                        if (last - start >= span) {
                            int substantial = 0;
                            for (int a = start; a <= last; a++) if (dark[index(horizontal, a, cross - 2, width)] || dark[index(horizontal, a, cross + 2, width)]) substantial++;
                            // A thinner partition needs a longer uninterrupted run. Short three-pixel furniture returns are excluded.
                            if (substantial >= (last - start + 1) * .6 || last - start >= Math.max(width, height) * .07)
                                for (int a = start; a <= last; a++) for (int d = -1; d <= 1; d++) wall[index(horizontal, a, cross + d, width)] = true;
                        }
                        start = -1;
                    }
                }
            }
        }
        // Short solid pillars can be shorter than the line-span threshold in both directions.
        // Keep their filled cores so glazing cannot leak through a facade step.
        int block = Math.max(9, (int) Math.round(Math.max(width, height) * .006));
        int[] integral = new int[(width + 1) * (height + 1)];
        for (int y = 0; y < height; y++) {
            int row = 0;
            for (int x = 0; x < width; x++) {
                row += dark[y * width + x] ? 1 : 0;
                integral[(y + 1) * (width + 1) + x + 1] = integral[y * (width + 1) + x + 1] + row;
            }
        }
        for (int y = 0; y + block <= height; y++) for (int x = 0; x + block <= width; x++) {
            int count = integral[(y + block) * (width + 1) + x + block] - integral[y * (width + 1) + x + block]
                - integral[(y + block) * (width + 1) + x] + integral[y * (width + 1) + x];
            if (count == block * block) for (int dy = 0; dy < block; dy++) for (int dx = 0; dx < block; dx++) wall[(y + dy) * width + x + dx] = true;
        }
        // Gap closure may use structural strokes only, never thin furniture/facade returns.
        boolean[] strokes = wall.clone();
        boolean[] thin = new boolean[dark.length];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int rgb = image.getRGB(x, y);
            thin[y * width + x] = structuralPixel(rgb, 215);
        }
        int facadeTop = height, facadeBottom = 0;
        for (int x = 0; x < width; x++) {
            int start = -1;
            for (int y = 0; y <= height; y++) {
                if (y < height && strokes[y * width + x]) { if (start < 0) start = y; }
                else if (start >= 0) {
                    if (y - start >= height * .3) { facadeTop = Math.min(facadeTop, start); facadeBottom = Math.max(facadeBottom, y - 1); }
                    start = -1;
                }
            }
        }
        // Restore long thin facade lines attached to walls, and short glazing returns. Never promote long cabinet fronts.
        for (boolean horizontal : new boolean[]{true, false, true, false}) {
            int alongSize = horizontal ? width : height, crossSize = horizontal ? height : width;
            int minimum = horizontal ? Math.max(70, (int) Math.round(width * .072)) : Math.max(12, (int) Math.round(height * .02));
            int maximum = horizontal ? alongSize : Math.max(50, (int) Math.round(height * .08));
            int radius = Math.max(5, (int) Math.round(Math.max(width, height) * .006));
            List<int[]> additions = new ArrayList<>();
            for (int cross = 0; cross < crossSize; cross++) {
                if (horizontal) {
                    // Only a facade spanning most of the page can restore a thin horizontal edge.
                    // Short furniture/cabinet fronts must not become room boundaries.
                    if (facadeTop == height || cross > facadeTop + radius * 2 && cross < facadeBottom - radius * 2) continue;
                    int occupied = 0;
                    for (int a = 0; a < width; a++) if (thin[cross * width + a]) occupied++;
                    if (occupied < width * .65) continue;
                }
                int start = -1, last = -1;
                for (int along = 0; along < alongSize + 3; along++) {
                    boolean solid = along < alongSize && thin[index(horizontal, along, cross, width)];
                    if (solid) { if (start < 0) start = along; last = along; }
                    else if (start >= 0 && along - last > 2) {
                        if (horizontal && last - start >= minimum) {
                            int firstSupport = -1, lastSupport = -1;
                            for (int a = start; a <= last; a++) if (attached(wall, true, a, cross, width, height, radius)) {
                                if (firstSupport < 0) firstSupport = a;
                                lastSupport = a;
                            }
                            if (cross >= facadeBottom - radius * 2 && firstSupport >= 0) additions.add(new int[]{cross, start, last});
                            else if (firstSupport >= 0 && lastSupport - firstSupport >= minimum) additions.add(new int[]{cross, firstSupport, lastSupport});
                        }
                        if (!horizontal && last - start >= minimum && last - start <= maximum && start > height * .65
                            && (attached(wall, false, start, cross, width, height, radius) || attached(wall, false, last, cross, width, height, radius) || attached(wall, false, (start + last) / 2, cross, width, height, radius))) additions.add(new int[]{cross, start, last});
                        start = -1;
                    }
                }
            }
            for (int[] run : additions) for (int a = run[1]; a <= run[2]; a++) wall[index(horizontal, a, run[0], width)] = true;
        }
        // Short facade returns can be horizontal as well as vertical. Require support at both ends.
        int returnLength = Math.max(20, (int) Math.round(Math.max(width, height) * .04));
        int returnRadius = Math.max(5, (int) Math.round(Math.max(width, height) * .006));
        for (int y = (int) (height * .65); y < height; y++) {
            int start = -1;
            for (int x = 0; x <= width; x++) {
                if (x < width && thin[y * width + x]) { if (start < 0) start = x; }
                else if (start >= 0) {
                    if (x - start >= 5 && x - start <= returnLength && attached(wall, true, start, y, width, height, returnRadius) && attached(wall, true, x - 1, y, width, height, returnRadius))
                        for (int a = start; a < x; a++) wall[y * width + a] = true;
                    start = -1;
                }
            }
        }
        // A door leaf can continue a partition to a perpendicular wall. Require visible thin-line
        // evidence beside a substantial structural run; do not grow a wall across blank floor.
        int maxGap = Math.max(30, (int) Math.round(Math.max(width, height) * .065));
        int support = Math.max(20, (int) Math.round(Math.max(width, height) * .055));
        boolean[] doorSupports = strokes.clone();
        int[] leafOrigins = new int[wall.length];
        Arrays.fill(leafOrigins, -1);
        int leafRadius = Math.max(2, (int) Math.round(Math.max(width, height) * .004));
        for (boolean horizontal : new boolean[]{true, false}) {
            int alongSize = horizontal ? width : height, crossSize = horizontal ? height : width;
            for (int cross = 0; cross < crossSize; cross++) {
                int start = -1;
                for (int along = 0; along <= alongSize; along++) {
                    boolean solid = along < alongSize && strokes[index(horizontal, along, cross, width)];
                    if (solid && start < 0) start = along;
                    if (!solid && start >= 0) {
                        int end = along - 1;
                        if (end - start + 1 >= support) for (int direction : new int[]{-1, 1}) {
                            int edge = direction < 0 ? start : end;
                            for (int distance = 1; distance <= maxGap; distance++) {
                                int a = edge + direction * distance;
                                if (a < 0 || a >= alongSize) break;
                                boolean visible = false;
                                for (int d = -leafRadius; d <= leafRadius && !visible; d++)
                                    if (cross + d >= 0 && cross + d < crossSize) visible = thin[index(horizontal, a, cross + d, width)];
                                if (!visible) break;
                                int cell = index(horizontal, a, cross, width);
                                doorSupports[cell] = true;
                                if (!strokes[cell]) leafOrigins[cell] = index(horizontal, edge, cross, width);
                            }
                        }
                        start = -1;
                    }
                }
            }
        }
        // Join bounded gaps, including a door ending against a perpendicular structural return.
        for (boolean horizontal : new boolean[]{true, false}) {
            int alongSize = horizontal ? width : height, crossSize = horizontal ? height : width;
            for (int cross = 0; cross < crossSize; cross++) {
                int previousStart = -1, previousEnd = -1, start = -1;
                for (int along = 0; along <= alongSize; along++) {
                    boolean solid = along < alongSize && doorSupports[index(horizontal, along, cross, width)];
                    if (solid && start < 0) start = along;
                    if (!solid && start >= 0) {
                        int end = along - 1;
                        // Require a substantial collinear wall run: short desk/cabinet edges cannot close an entire room.
                        if (previousStart >= 0 && start - previousEnd <= maxGap && (previousEnd - previousStart + 1 >= support || end - start + 1 >= support)) {
                            for (int a = previousEnd + 1; a < start; a++) wall[index(horizontal, a, cross, width)] = true;
                            // Promote only the visible leaf actually used by a closure, not every cabinet return.
                            for (int a : new int[]{previousEnd, start}) {
                                int cell = index(horizontal, a, cross, width), origin = leafOrigins[cell];
                                if (origin >= 0) {
                                    int step = cell % width == origin % width ? width : 1;
                                    for (int c = Math.min(cell, origin); c <= Math.max(cell, origin); c += step) wall[c] = true;
                                }
                            }
                        }
                        previousStart = start; previousEnd = end; start = -1;
                    }
                }
            }
        }

        return wall;
    }

    private static int index(boolean horizontal, int along, int cross, int width) { return horizontal ? cross * width + along : along * width + cross; }
    private static boolean attached(boolean[] wall, boolean horizontal, int along, int cross, int width, int height, int radius) {
        int x = horizontal ? along : cross, y = horizontal ? cross : along;
        for (int dy = -radius; dy <= radius; dy++) for (int dx = -radius; dx <= radius; dx++) {
            int xx = x + dx, yy = y + dy;
            if (xx >= 0 && xx < width && yy >= 0 && yy < height && wall[yy * width + xx]) return true;
        }
        return false;
    }

    private static Region flood(int seed, int id, boolean[] wall, int[] labels, int[] queue, int width, int height) {
        int read = 0, size = 1, minX = width, minY = height, maxX = 0, maxY = 0;
        queue[0] = seed; labels[seed] = id; boolean border = false;
        while (read < size) {
            int cell = queue[read++], x = cell % width, y = cell / width;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            if (x == 0 || y == 0 || x == width - 1 || y == height - 1) border = true;
            for (int direction = 0; direction < 4; direction++) {
                int neighbour = switch (direction) { case 0 -> x > 0 ? cell - 1 : -1; case 1 -> x < width - 1 ? cell + 1 : -1; case 2 -> y > 0 ? cell - width : -1; default -> y < height - 1 ? cell + width : -1; };
                if (neighbour >= 0 && !wall[neighbour] && labels[neighbour] == 0) { labels[neighbour] = id; queue[size++] = neighbour; }
            }
        }
        if (border || size < width * height * .001 || size > width * height * .4) return new Region(id, size, null);
        // Trace grid edges with the flooded interior on the right. Avoid Area's union of thousands of touching row paths.
        int startX = minX;
        while (labels[minY * width + startX] != id) startX++;
        int x = startX, y = minY, direction = 0, previousDirection = -1, steps = 0;
        List<List<Double>> largest = new ArrayList<>();
        int[] dx = {1, 0, -1, 0}, dy = {0, 1, 0, -1};
        do {
            if (direction != previousDirection) largest.add(List.of((double) x, (double) y));
            previousDirection = direction;
            x += dx[direction]; y += dy[direction];
            boolean found = false;
            for (int turn : new int[]{1, 0, 3, 2}) {
                int next = (direction + turn) % 4;
                int rightX = x + (next == 1 || next == 2 ? -1 : 0);
                int rightY = y + (next == 2 || next == 3 ? -1 : 0);
                int leftX = rightX + (next == 1 ? 1 : next == 3 ? -1 : 0);
                int leftY = rightY + (next == 0 ? -1 : next == 2 ? 1 : 0);
                if (inside(labels, rightX, rightY, width, height, id) && !inside(labels, leftX, leftY, width, height, id)) {
                    direction = next; found = true; break;
                }
            }
            if (!found || largest.size() > 512 || ++steps > 32L * (width + height)) return new Region(id, size, null);
        } while (x != startX || y != minY || direction != 0);
        if (largest.size() < 3) return new Region(id, size, null);
        int split = 1; double far = 0; var first = largest.get(0);
        for (int i = 1; i < largest.size(); i++) { var p = largest.get(i); double d = Math.hypot(p.get(0) - first.get(0), p.get(1) - first.get(1)); if (d > far) { far = d; split = i; } }
        List<List<Double>> a = simplify(new ArrayList<>(largest.subList(0, split + 1))), tail = new ArrayList<>(largest.subList(split, largest.size())); tail.add(first);
        List<List<Double>> b = simplify(tail), ring = new ArrayList<>(a.subList(0, a.size() - 1)); ring.addAll(b.subList(0, b.size() - 1));

        // Furniture leakage produces noisy outlines; retain the candidate instead of promoting that region.
        if (ring.size() > 32 || Math.abs(area(ring) - size) / size > .05) return new Region(id, size, null);
        return new Region(id, size, ring);
    }

    private static boolean inside(int[] labels, int x, int y, int width, int height, int id) {
        return x >= 0 && x < width && y >= 0 && y < height && labels[y * width + x] == id;
    }

    private static List<List<Double>> simplify(List<List<Double>> points) {
        if (points.size() <= 2) return points;
        var a = points.get(0); var b = points.get(points.size() - 1); int at = -1; double max = 2;
        for (int i = 1; i < points.size() - 1; i++) { var p = points.get(i); double d = java.awt.geom.Line2D.ptSegDist(a.get(0), a.get(1), b.get(0), b.get(1), p.get(0), p.get(1)); if (d > max) { max = d; at = i; } }
        if (at < 0) return List.of(a, b);
        var left = simplify(new ArrayList<>(points.subList(0, at + 1))); var right = simplify(new ArrayList<>(points.subList(at, points.size())));
        List<List<Double>> result = new ArrayList<>(left.subList(0, left.size() - 1)); result.addAll(right); return result;
    }

    private static boolean sameRoom(JsonNode rooms, int index, List<List<Double>> ring) {
        var initial = points(rooms.get(index).path("polygon"));
        double ratio = area(ring) / area(initial);
        if (ratio < .25 || ratio > 4) return false;
        var after = shape(ring); var before = shape(initial); var intersection = new Area(after); intersection.intersect(before);
        if (intersection.isEmpty()) return false;
        for (int i = 0; i < rooms.size(); i++) if (i != index && ENCLOSED.contains(rooms.get(i).path("roomType").asText())) {
            var other = points(rooms.get(i).path("polygon")); if (other == null) continue;
            double x = other.stream().mapToDouble(p -> p.get(0)).average().orElse(0), y = other.stream().mapToDouble(p -> p.get(1)).average().orElse(0);
            if (!before.contains(x, y) && after.contains(x, y)) return false;
        }
        return true;
    }
    private static Area shape(List<List<Double>> points) { var path = new Path2D.Double(); path.moveTo(points.get(0).get(0), points.get(0).get(1)); for (var p : points.subList(1, points.size())) path.lineTo(p.get(0), p.get(1)); path.closePath(); return new Area(path); }
    private static double area(List<List<Double>> points) { double sum = 0; for (int i = 0; i < points.size(); i++) { var a = points.get(i); var b = points.get((i + 1) % points.size()); sum += a.get(0) * b.get(1) - b.get(0) * a.get(1); } return Math.abs(sum) / 2; }
    private static List<List<Double>> points(JsonNode node) {
        if (!node.isArray() || node.size() < 3 || node.size() > 256) return null;
        List<List<Double>> points = new ArrayList<>();
        for (var p : node) { if (!p.isArray() || p.size() != 2 || !p.get(0).isNumber() || !p.get(1).isNumber()) return null; double x = p.get(0).asDouble(), y = p.get(1).asDouble(); if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0 || x > 1000 || y > 1000) return null; points.add(List.of(x, y)); }
        return points;
    }
    private static boolean valid(List<List<Double>> points, SpatialGeometryService service) { var geometry = new SpatialGeometry(); geometry.setCoordinateSystem(SpatialGeometry.COORDINATE_SOURCE_PAGE); geometry.setOuter(points); return service.validate(geometry).isEmpty(); }
}
