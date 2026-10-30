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
    private static final Set<String> ENCLOSED = Set.of("EXECUTIVE_OFFICE", "MEETING_ROOM", "KITCHEN", "BATHROOM", "STUDY",
        "TRAINING_ROOM", "PHONE_BOOTH", "STORAGE", "SERVER_ROOM");
    private record Region(int id, int size, List<List<Double>> ring) {}

    static ObjectNode refine(JsonNode initial, VisionImagePreparer.PreparedImage page, ObjectMapper mapper, SpatialGeometryService geometry) {
        return refine(initial, page, mapper, geometry, mapper.createObjectNode());
    }

    static ObjectNode refine(JsonNode initial, VisionImagePreparer.PreparedImage page, ObjectMapper mapper, SpatialGeometryService geometry, JsonNode localization) {
        ObjectNode result = initial.deepCopy();
        if (!initial.path("rooms").isArray()) return result;
        boolean semanticPipeline = initial.path("labels").isArray();
        for (var room : initial.path("rooms")) if (room.path("semanticOnly").asBoolean()) semanticPipeline = true;
        if (semanticPipeline) reconcileSemanticLabels(result, localization);
        initial = result.deepCopy();
        ArrayNode warnings = mapper.createArrayNode();
        if (initial.path("warnings").isArray()) warnings.addAll((ArrayNode) initial.path("warnings"));
        result.set("warnings", warnings);
        ObjectNode evidence = result.putObject("contourRefinement");
        evidence.put("method", "PNG_WALL_TOPOLOGY_V1");
        evidence.putArray("stages").add("STRUCTURAL_WALL_MASK").add("WALL_GRAPH").add("DOOR_CLOSURE").add("POLYGONIZE");
        evidence.put("semanticBoundaryPolicy", semanticPipeline ? "QWEN_LABELS_ONLY" : "MODEL_CANDIDATES_WITH_CONSERVATIVE_WALL_REFINEMENT");
        Map<Integer, List<Double>> anchors = roomAnchors(initial.path("rooms"), localization);
        boolean located = "DETECTED".equals(localization.path("status").asText());
        evidence.put("seedSource", located
            ? localization.path("primaryFallbackCount").asInt() > 0 ? "PRIMARY_AND_INDEPENDENT_LABELS" : "INDEPENDENT_LABEL_DETECTION"
            : semanticPipeline ? "UNLABELLED_WALL_COMPONENTS" : "MODEL_POLYGON_CENTRE");
        if ("UNAVAILABLE".equals(localization.path("status").asText())) warnings.add("房名独立定位未完成，保留来源证据并尝试未命名墙网候选，请复核");
        ArrayNode records = evidence.putArray("rooms");
        int accepted = 0, attempted = 0;
        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(page.png()));
            // Keep the same detail budget as the canonical model input. The old 2048px
            // secondary resize discarded thin partitions before topology reconstruction.
            double factor = Math.min(1, 3072.0 / Math.max(page.width(), page.height()));
            BufferedImage image = new BufferedImage(Math.max(1, (int) Math.round(page.width() * factor)), Math.max(1, (int) Math.round(page.height() * factor)), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            try { graphics.drawImage(original, 0, 0, image.getWidth(), image.getHeight(), null); } finally { graphics.dispose(); }
            int width = image.getWidth(), height = image.getHeight();
            evidence.put("analysisWidth", width).put("analysisHeight", height)
                .put("wallMaskSource", "STRUCTURAL_PIXEL_MASK")
                .put("wallGraph", "AXIS_ALIGNED_RUN_NETWORK")
                .put("doorClosure", "SUPPORTED_GAP_EXTENSION")
                .put("polygonizer", "CONNECTED_COMPONENT_BOUNDARY")
                .put("estimatedDoorClosures", true).put("requiresReview", true);
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
                var anchor = anchors.get(index);
                if (candidate == null && anchor == null) continue;
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
            Set<Integer> weakRecovered = new HashSet<>();
            Set<Integer> rectangleRecovered = new HashSet<>();
            boolean[] weakWalls = null;
            double pixelsPerPrintedArea = printedAreaRatio(initial.path("rooms"), proposals, assignments, anchors, width, height);
            if (Double.isFinite(pixelsPerPrintedArea)) evidence.put("pixelAreaPerPrintedM2", pixelsPerPrintedArea);
            if (located && Double.isFinite(pixelsPerPrintedArea)) {
                weakWalls = wallMask(image, true);
                int[] weakLabels = new int[width * height];
                for (int index = 0; index < initial.path("rooms").size(); index++) {
                    JsonNode room = initial.path("rooms").get(index);
                    if (!eligible(room, index, true, anchors)) continue;
                    Region current = proposals.get(index);
                    double printedArea = printedArea(room), expected = printedArea * pixelsPerPrintedArea;
                    if (current != null && ownsLabel(initial.path("rooms"), index, normalized(current, width, height), anchors)
                        && (!Double.isFinite(expected) || current.size() >= expected * .85 && current.size() <= expected * 1.15)) continue;
                    var anchor = anchors.get(index);
                    int x = (int) Math.round(anchor.get(0) * width / 1000), y = (int) Math.round(anchor.get(1) * height / 1000);
                    if (x < 0 || x >= width || y < 0 || y >= height) continue;
                    if (!Double.isFinite(expected)) continue;
                    int cell = y * width + x, id = weakLabels[cell];
                    Region region = null;
                    if (!weakWalls[cell]) {
                        if (id == 0) { id = nextId++; region = flood(cell, id, weakWalls, weakLabels, queue, width, height); cache.put(id, region); }
                        else region = cache.get(id);
                    }
                    if (region == null || region.ring() == null || region.size() < expected * .85 || region.size() > expected * 1.15
                        || !ownsLabel(initial.path("rooms"), index, normalized(region, width, height), anchors)) {
                        region = printedAreaRectangle(index, anchors, weakWalls, weakLabels, queue, cache, width, height, expected, nextId++);
                        if (region == null) continue;
                        id = region.id();
                        rectangleRecovered.add(index);
                    }
                    if (conflictsWithGrounded(index, region, proposals, initial.path("rooms"), anchors, width, height)) continue;
                    region = new Region(region.id(), region.size(), withoutNeedleNotches(region.ring()));
                    if (current != null) assignments.merge(current.id(), -1, Integer::sum);
                    proposals.put(index, region); assignments.merge(id, 1, Integer::sum); weakRecovered.add(index);
                }
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
                double expected = printedArea(room) * pixelsPerPrintedArea;
                boolean areaConflict = region != null && Double.isFinite(expected)
                    && (region.size() < expected * .65 || region.size() > expected * 1.35);
                var sourceCandidate = points(room.path("polygon"));
                boolean candidateConflict = validLabelBox(room.path("labelBbox"))
                    && global != null && sourceCandidate != null && valid(sourceCandidate, geometry)
                    && !Double.isFinite(expected) && !consistentCandidate(sourceCandidate, global);
                if (global == null || assignments.get(region.id()) != 1 || !valid(global, geometry)
                    || areaConflict || candidateConflict
                    || !(located ? ownsLabel(initial.path("rooms"), index, global, anchors) : sameRoom(initial.path("rooms"), index, global))) {
                    record.put("status", "UNRESOLVED").put("reason", region == null ? "NO_CLOSED_WALL_REGION"
                        : areaConflict ? "PRINTED_AREA_CONFLICT" : candidateConflict ? "MODEL_CANDIDATE_CONFLICT"
                        : assignments.get(region.id()) != 1 ? "AMBIGUOUS_REGION" : "GEOMETRY_OR_OWNERSHIP_CONFLICT");
                    ((ObjectNode) result.path("rooms").get(index)).put("confidence", "low");
                    warnings.add(room.path("label").asText() + "：墙线未可靠闭合或归属有歧义，保留识别候选，请复核");
                    var candidate = points(room.path("polygon"));
                    if (located && candidate != null && !shape(candidate).contains(anchor.get(0), anchor.get(1)))
                        warnings.add(room.path("label").asText() + "：房名定位与候选轮廓不符，该轮廓不能作为准确识别结果");
                    continue;
                }
                ((ObjectNode) result.path("rooms").get(index)).set("polygon", mapper.valueToTree(global));
                String labelSource = labelSource(localization, anchor);
                boolean independent = located && !"PRIMARY_SEMANTIC_LABEL".equals(labelSource);
                ((ObjectNode) result.path("rooms").get(index)).put("contourGrounded", independent)
                    .put("wallRegionVerified", true).put("contourGroundingSource", labelSource);
                if (!independent) ((ObjectNode) result.path("rooms").get(index)).put("confidence", "low");
                record.put("status", independent ? "GROUNDED" : "WALL_REGION_NEEDS_REVIEW")
                    .put("labelSource", labelSource).put("regionPixels", region.size());
                if (weakRecovered.contains(index)) {
                    record.put("method", rectangleRecovered.contains(index) ? "AREA_CHECKED_PARTITION_MERGE" : "CONNECTED_THIN_STROKES");
                    ((ObjectNode) result.path("rooms").get(index)).put("confidence", independent ? "mid" : "low");
                }
                accepted++;
            }
            recoverOpenSpaces(result, localization, anchors, walls, weakWalls, image, mapper, geometry, warnings);
            if (semanticPipeline) discoverUnlabelledRegions(result, walls, image, mapper, geometry);
            if (accepted > 0) warnings.add("墙线核查含门洞估计补线，空间轮廓仍需按图复核");
            evidence.put("status", accepted == 0 ? "UNRESOLVED" : accepted == initial.path("rooms").size() ? "APPLIED" : "PARTIAL");
        } catch (java.io.IOException | IllegalArgumentException failure) {
            warnings.add("图像墙线核查未完成，保留初次识别候选，请复核");
            evidence.put("status", "FAILED");
        }
        evidence.put("attemptedCount", attempted).put("acceptedCount", accepted);
        return result;
    }

    /** Secondary OCR may see an occurrence omitted by the semantic request. Keep it without inventing its type. */
    private static void reconcileSemanticLabels(ObjectNode result, JsonNode localization) {
        ArrayNode rooms = (ArrayNode) result.path("rooms");
        Map<String, Integer> detected = new HashMap<>();
        for (var label : localization.path("labels")) {
            if (!validLabelBox(label.path("bbox"))) continue;
            String text = label.path("text").asText(""); String name = labelKey(text);
            if (name.isEmpty()) continue;
            int occurrences = detected.merge(name, 1, Integer::sum), existing = 0;
            String type = null;
            for (var room : rooms) if (name.equals(labelKey(room.path("label").asText("")))) {
                existing++;
                String candidateType = room.path("roomType").asText("OTHER");
                type = type == null ? candidateType : type.equals(candidateType) ? type : "OTHER";
            }
            if (occurrences <= existing || rooms.size() >= 200) continue;
            if (type == null) type = text.matches(".*(?:开放办公|公共办公|集中办公|办公区|工位区).*") ? "OPEN_OFFICE" : "OTHER";
            ObjectNode added = rooms.addObject().put("label", text).put("roomType", type).put("confidence", "low")
                .put("semanticOnly", true).put("semanticRecovery", "LOCATED_LABEL_MISSING_FROM_PRIMARY");
            added.putArray("polygon"); added.set("labelBbox", label.path("bbox").deepCopy());
        }
    }

    private static String labelSource(JsonNode localization, List<Double> anchor) {
        if (anchor == null) return "MODEL_POLYGON_CENTRE";
        for (var label : localization.path("labels")) {
            var box = label.path("bbox");
            if (validLabelBox(box) && Math.abs((box.get(0).asDouble() + box.get(2).asDouble()) / 2 - anchor.get(0)) < .01
                && Math.abs((box.get(1).asDouble() + box.get(3).asDouble()) / 2 - anchor.get(1)) < .01)
                return label.path("source").asText("INDEPENDENT_LABEL_DETECTION");
        }
        return "INDEPENDENT_LABEL_DETECTION";
    }

    /** Exhaustive wall regions are independent of OCR. Conservative, low-confidence candidates only. */
    private static void discoverUnlabelledRegions(ObjectNode result, boolean[] walls, BufferedImage image,
        ObjectMapper mapper, SpatialGeometryService geometry) {
        int width = image.getWidth(), height = image.getHeight();
        int[] labels = new int[walls.length], queue = new int[walls.length];
        ArrayNode rooms = (ArrayNode) result.path("rooms");
        List<Area> occupied = new ArrayList<>();
        for (var room : rooms) {
            var ring = points(room.path("polygon"));
            if (ring != null && valid(ring, geometry)) occupied.add(shape(ring));
        }
        int next = 1, found = 0;
        for (int cell = 0; cell < walls.length && rooms.size() < 200; cell++) {
            if (walls[cell] || labels[cell] != 0) continue;
            Region region = flood(cell, next++, walls, labels, queue, width, height);
            if (region.ring() == null || region.size() < walls.length * .003) continue;
            var bounds = shape(region.ring()).getBounds2D();
            double narrow = Math.min(bounds.getWidth(), bounds.getHeight()), wide = Math.max(bounds.getWidth(), bounds.getHeight());
            // Thin leftover strips between parallel walls are not evidence of an unlabelled room.
            if (narrow < Math.max(width, height) * .02 || narrow / wide < .2) continue;
            var ring = normalized(region, width, height); var outline = shape(ring);
            if (!valid(ring, geometry) || intersectsAny(outline, occupied)) continue;
            ObjectNode room = rooms.addObject().put("roomType", "OTHER").put("confidence", "low")
                .put("contourGrounded", false).put("contourRecovery", "UNLABELLED_WALL_REGION");
            room.putNull("label"); room.set("polygon", mapper.valueToTree(ring));
            occupied.add(outline); found++;
        }
        result.putObject("regionDiscovery").put("method", "ALL_CLOSED_WALL_COMPONENTS")
            .put("unnamedCount", found).put("requiresReview", true);
    }

    /** Open floor is a separate, explicitly estimated recovery path. Its existence must not depend
     * on an already valid model polygon or be mistaken for an enclosed architectural room. */
    private static void recoverOpenSpaces(ObjectNode result, JsonNode localization, Map<Integer, List<Double>> anchors,
        boolean[] structuralWalls, boolean[] weakWalls, BufferedImage image, ObjectMapper mapper,
        SpatialGeometryService geometry, ArrayNode warnings) {
        ArrayNode rooms = (ArrayNode) result.path("rooms");
        if (!"DETECTED".equals(localization.path("status").asText())) return;
        for (var label : localization.path("labels")) {
            String text = label.path("text").asText(""); var box = label.path("bbox");
            if (!text.matches(".*(?:开放办公|公共办公|集中办公|办公区|工位区).*")) continue;
            boolean present = false;
            for (var room : rooms) if (labelKey(room.path("label").asText()).equals(labelKey(text))) present = true;
            if (present || !validLabelBox(box)) continue;
            if (rooms.size() >= 200) { warnings.add("开放区文字已检测到，但候选数量达到上限，请复核空间清单"); break; }
            int index = rooms.size();
            rooms.addObject().put("label", text).put("roomType", "OPEN_OFFICE").put("confidence", "low").putArray("polygon");
            anchors.put(index, List.of((box.get(0).asDouble() + box.get(2).asDouble()) / 2,
                (box.get(1).asDouble() + box.get(3).asDouble()) / 2));
        }
        List<Integer> openIndices = new ArrayList<>();
        for (int i = 0; i < rooms.size(); i++) if ("OPEN_OFFICE".equals(rooms.get(i).path("roomType").asText())) {
            var ring = points(rooms.get(i).path("polygon")); var anchor = anchors.get(i);
            if (ring == null || !valid(ring, geometry) || anchor != null && !shape(ring).contains(anchor.get(0), anchor.get(1))) openIndices.add(i);
        }
        if (openIndices.isEmpty()) return;
        int width = image.getWidth(), height = image.getHeight();
        if (weakWalls == null) weakWalls = wallMask(image, true);
        boolean[] blocked = structuralWalls.clone();
        // Actual grounded rooms remain excluded even if a faint furniture edge divides their weak mask.
        List<Area> grounded = new ArrayList<>();
        for (var room : rooms) if (room.path("contourGrounded").asBoolean() || room.path("wallRegionVerified").asBoolean()) {
            var ring = points(room.path("polygon"));
            if (ring == null) continue;
            Area shape = shape(ring.stream().map(p -> List.of(p.get(0) * width / 1000, p.get(1) * height / 1000)).toList());
            grounded.add(shape); var b = shape.getBounds2D();
            for (int y = Math.max(0, (int) b.getMinY()); y < Math.min(height, (int) Math.ceil(b.getMaxY())); y++)
                for (int x = Math.max(0, (int) b.getMinX()); x < Math.min(width, (int) Math.ceil(b.getMaxX())); x++)
                    if (shape.contains(x + .5, y + .5)) blocked[y * width + x] = true;
        }
        int[] labels = new int[width * height], queue = new int[width * height];
        Map<Integer, Region> regions = new HashMap<>(); Set<Integer> excluded = new HashSet<>(); int next = 1;
        for (var entry : anchors.entrySet()) {
            int index = entry.getKey(); var room = rooms.get(index);
            if ("OPEN_OFFICE".equals(room.path("roomType").asText()) || room.path("contourGrounded").asBoolean()
                || room.path("wallRegionVerified").asBoolean()) continue;
            var anchor = entry.getValue(); JsonNode box = null;
            for (var label : localization.path("labels")) {
                var candidateBox = label.path("bbox");
                if (validLabelBox(candidateBox)
                    && Math.abs((candidateBox.get(0).asDouble() + candidateBox.get(2).asDouble()) / 2 - anchor.get(0)) < .01
                    && Math.abs((candidateBox.get(1).asDouble() + candidateBox.get(3).asDouble()) / 2 - anchor.get(1)) < .01) { box = candidateBox; break; }
            }
            // A text centre may lie on a cubicle partition. Use the inner text box, not a made-up room box.
            for (double fy : new double[]{.25,.5,.75}) for (double fx : new double[]{.25,.5,.75}) {
                double nx = box == null ? anchor.get(0) : box.get(0).asDouble() * (1 - fx) + box.get(2).asDouble() * fx;
                double ny = box == null ? anchor.get(1) : box.get(1).asDouble() * (1 - fy) + box.get(3).asDouble() * fy;
                int x = (int) Math.round(nx * width / 1000), y = (int) Math.round(ny * height / 1000);
                if (x < 0 || x >= width || y < 0 || y >= height || weakWalls[y * width + x]) continue;
                int cell = y * width + x, id = labels[cell]; Region region;
                if (id == 0) { id = next++; region = flood(cell, id, weakWalls, labels, queue, width, height); regions.put(id, region); }
                else region = regions.get(id);
                if (region == null || region.ring() == null || region.size() > width * height * .12) continue;
                var outline = shape(normalized(region, width, height));
                boolean conflict = false;
                for (var other : anchors.entrySet()) if (other.getKey() != index && outline.contains(other.getValue().get(0), other.getValue().get(1))) conflict = true;
                if (!conflict) excluded.add(id);
            }
        }
        for (int cell = 0; cell < blocked.length; cell++) if (excluded.contains(labels[cell])) blocked[cell] = true;
        ObjectNode evidence = result.putObject("openSpaceRecovery").put("method", "STRUCTURAL_FREE_FLOOR").put("requiresReview", true);
        ArrayNode records = evidence.putArray("rooms");
        Map<Integer, Region> proposals = new HashMap<>(); Map<Integer, Integer> assignments = new HashMap<>();
        Arrays.fill(labels, 0); regions.clear(); next = 1;
        for (int index : openIndices) {
            var anchor = anchors.get(index);
            var candidate = points(rooms.get(index).path("polygon"), false);
            if (anchor == null && candidate != null) {
                var bounds = shape(candidate).getBounds2D(); anchor = List.of(bounds.getCenterX(), bounds.getCenterY());
            }
            if (anchor == null) continue;
            int x = (int) Math.round(anchor.get(0) * width / 1000), y = (int) Math.round(anchor.get(1) * height / 1000);
            if (x < 0 || x >= width || y < 0 || y >= height || blocked[y * width + x]) continue;
            int cell = y * width + x, id = labels[cell]; Region region;
            if (id == 0) { id = next++; region = flood(cell, id, blocked, labels, queue, width, height); regions.put(id, region); }
            else region = regions.get(id);
            if (region == null || region.ring() == null || region.size() < width * height * .03) continue;
            var outline = shape(normalized(region, width, height)); boolean conflict = false;
            for (var other : anchors.entrySet()) if (other.getKey() != index && outline.contains(other.getValue().get(0), other.getValue().get(1))) conflict = true;
            if (intersectsAny(shape(region.ring()), grounded)) conflict = true;
            if (!conflict && valid(normalized(region, width, height), geometry)) { proposals.put(index, region); assignments.merge(id, 1, Integer::sum); }
        }
        int recovered = 0;
        for (int index : openIndices) {
            var room = (ObjectNode) rooms.get(index); var region = proposals.get(index);
            var record = records.addObject().put("candidateIndex", index).put("label", room.path("label").asText());
            record.put("seedSource", anchors.containsKey(index) ? labelSource(localization, anchors.get(index)) : "MODEL_CANDIDATE_CENTRE");
            if (region == null || assignments.get(region.id()) != 1) {
                record.put("status", "UNRESOLVED"); warnings.add(room.path("label").asText() + "：开放区边界未恢复，不能将漏检视为图中没有该空间"); continue;
            }
            var ring = withoutNeedleNotches(region.ring());
            var normalized = ring.stream().map(p -> List.of(p.get(0) * 1000 / width, p.get(1) * 1000 / height)).toList();
            if (!valid(normalized, geometry) || intersectsAny(shape(ring), grounded)) { record.put("status", "UNRESOLVED"); continue; }
            room.set("polygon", mapper.valueToTree(normalized));
            room.put("confidence", "low").put("contourGrounded", false).put("contourRecovery", "STRUCTURAL_FREE_FLOOR");
            if (anchors.containsKey(index)) room.put("contourGroundingSource", labelSource(localization, anchors.get(index)));
            record.put("status", "RECOVERED_NEEDS_REVIEW").put("regionPixels", region.size()); recovered++;
            warnings.add(room.path("label").asText() + "：已按墙线及其他房名所在区域补回开放区；临近未解决空间的功能分界仍需复核");
        }
        evidence.put("recoveredCount", recovered).put("status", recovered == openIndices.size() ? "RECOVERED_NEEDS_REVIEW" : "PARTIAL");
    }

    private static boolean intersectsAny(Area shape, List<Area> others) {
        for (Area other : others) {
            var intersection = new Area(shape); intersection.intersect(other);
            if (!intersection.isEmpty()) return true;
        }
        return false;
    }

    private static boolean validLabelBox(JsonNode box) {
        return RoomLabelGrounding.validBox(box);
    }

    private static double printedArea(JsonNode room) {
        var match = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(?:m²|m2|㎡)", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(room.path("dimensionText").asText(""));
        if (!match.find()) return Double.NaN;
        try { double area = Double.parseDouble(match.group(1)); return area > 0 && area < 100000 ? area : Double.NaN; }
        catch (NumberFormatException failure) { return Double.NaN; }
    }

    /** Relative area-text consistency is evidence for shape selection, never physical calibration. */
    private static double printedAreaRatio(JsonNode rooms, Map<Integer, Region> proposals, Map<Integer, Integer> assignments,
        Map<Integer, List<Double>> anchors, int width, int height) {
        List<Double> ratios = new ArrayList<>();
        for (var entry : proposals.entrySet()) {
            double printed = printedArea(rooms.get(entry.getKey()));
            if (Double.isFinite(printed) && entry.getValue().ring() != null && assignments.get(entry.getValue().id()) == 1
                && ownsLabel(rooms, entry.getKey(), normalized(entry.getValue(), width, height), anchors))
                ratios.add(entry.getValue().size() / printed);
        }
        if (ratios.size() < 3) return Double.NaN;
        Collections.sort(ratios); double median = ratios.get(ratios.size() / 2);
        if (ratios.stream().filter(value -> value >= median * .75 && value <= median * 1.25).count() < ratios.size() * .8) return Double.NaN;
        return median;
    }

    /** A label landing on a partition must not select a cubicle-sized fragment. Require four
     * supported sides, exclusive room-name ownership and agreement with printed relative area. */
    private static Region printedAreaRectangle(int index, Map<Integer, List<Double>> anchors, boolean[] wall,
        int[] labels, int[] queue, Map<Integer, Region> cache, int width, int height, double expected, int id) {
        var anchor = anchors.get(index); double x = anchor.get(0) * width / 1000, y = anchor.get(1) * height / 1000;
        int radius = (int) Math.ceil(Math.sqrt(expected) * 2);
        int minX = Math.max(0, (int) x - radius), maxX = Math.min(width - 1, (int) x + radius);
        int minY = Math.max(0, (int) y - radius), maxY = Math.min(height - 1, (int) y + radius);
        int[] xs = linePositions(wall, true, minX, maxX, minY, maxY, width, expected);
        int[] ys = linePositions(wall, false, minY, maxY, minX, maxX, width, expected);
        // Recover whole neighbouring partition fragments, rather than extending a small fragment
        // to an arbitrary area-matching rectangle. Each flood uses the full page, not a clipped ROI.
        Map<Integer, Region> fragments = new HashMap<>();
        int step = Math.max(4, (int) Math.sqrt(expected) / 10);
        int fragmentId = -1 - cache.size();
        for (int sy = minY; sy <= maxY; sy += step) for (int sx = minX; sx <= maxX; sx += step) {
            int cell = sy * width + sx;
            if (wall[cell]) continue;
            int known = labels[cell]; Region fragment;
            if (known == 0) {
                known = fragmentId--; fragment = flood(cell, known, wall, labels, queue, width, height); cache.put(known, fragment);
            } else fragment = cache.get(known);
            if (fragment != null && fragment.ring() != null && fragment.size() >= expected * .15) fragments.put(known, fragment);
        }
        Region best = null; double bestScore = 0;
        for (int left : xs) for (int right : xs) {
            if (left >= x - 2 || right <= x + 2 || right <= left) continue;
            for (int top : ys) for (int bottom : ys) {
                if (top >= y - 2 || bottom <= y + 2 || bottom <= top) continue;
                double area = (double) (right - left) * (bottom - top), aspect = (double) (right - left) / (bottom - top);
                if (area < expected * .8 || area > expected * 1.25 || aspect < .3 || aspect > 3.5) continue;
                boolean exclusive = true;
                for (var other : anchors.entrySet()) if (other.getKey() != index) {
                    double ox = other.getValue().get(0) * width / 1000, oy = other.getValue().get(1) * height / 1000;
                    if (ox > left && ox < right && oy > top && oy < bottom) { exclusive = false; break; }
                }
                if (!exclusive) continue;
                int covered = 0, pieces = 0;
                for (Region fragment : fragments.values()) {
                    var bounds = shape(fragment.ring()).getBounds2D();
                    if (bounds.getMinX() >= left && bounds.getMaxX() <= right && bounds.getMinY() >= top && bounds.getMaxY() <= bottom) {
                        covered += fragment.size(); pieces++;
                    }
                }
                if (pieces < 2 || covered < area * .85) continue;
                double a = edgeSupport(wall, true, top, left, right, width, height), b = edgeSupport(wall, true, bottom, left, right, width, height);
                double c = edgeSupport(wall, false, left, top, bottom, width, height), d = edgeSupport(wall, false, right, top, bottom, width, height);
                if (Math.min(Math.min(a, b), Math.min(c, d)) < .55) continue;
                double score = (a + b + c + d) / 4 - .3 * Math.abs(Math.log(area / expected));
                if (score <= bestScore) continue;
                bestScore = score;
                int innerLeft = insideWallBand(wall, false, left, top, bottom, 1, width, height);
                int innerRight = insideWallBand(wall, false, right, top, bottom, -1, width, height);
                int innerTop = insideWallBand(wall, true, top, innerLeft, innerRight, 1, width, height);
                int innerBottom = insideWallBand(wall, true, bottom, innerLeft, innerRight, -1, width, height);
                if (innerLeft >= innerRight || innerTop >= innerBottom) continue;
                best = new Region(id, (innerRight - innerLeft) * (innerBottom - innerTop),
                    List.of(List.of((double) innerLeft, (double) innerTop), List.of((double) innerRight, (double) innerTop),
                        List.of((double) innerRight, (double) innerBottom), List.of((double) innerLeft, (double) innerBottom)));
            }
        }
        return best;
    }

    private static int insideWallBand(boolean[] wall, boolean horizontal, int cross, int from, int to,
        int direction, int width, int height) {
        int limit = Math.max(3, (int) Math.round(Math.max(width, height) * .01));
        for (int distance = 0; distance <= limit; distance++) {
            int c = cross + direction * distance, occupied = 0;
            if (c < 0 || c >= (horizontal ? height : width)) break;
            for (int along = from; along <= to; along++) if (wall[index(horizontal, along, c, width)]) occupied++;
            if (occupied < (to - from + 1) * .2) return c;
            if (distance == 0 && occupied < (to - from + 1) * .6) return cross;
        }
        return cross;
    }

    /** A one/two-pixel slit from an inferred wall extension is not a room-sized recess. */
    private static List<List<Double>> withoutNeedleNotches(List<List<Double>> original) {
        List<List<Double>> ring = new ArrayList<>(original);
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < ring.size() && ring.size() > 3; i++) {
                var a = ring.get((i + ring.size() - 1) % ring.size()); var b = ring.get(i); var c = ring.get((i + 1) % ring.size());
                double gap = Math.hypot(c.get(0) - a.get(0), c.get(1) - a.get(1));
                double distance = java.awt.geom.Line2D.ptSegDist(a.get(0), a.get(1), c.get(0), c.get(1), b.get(0), b.get(1));
                if (gap <= 3 && distance > 3 || distance < .01) { ring.remove(i); changed = true; break; }
            }
        } while (changed);
        return Math.abs(area(ring) - area(original)) <= area(original) * .01 ? ring : original;
    }

    private static int[] linePositions(boolean[] wall, boolean vertical, int min, int max, int from, int to, int width, double expected) {
        List<Integer> positions = new ArrayList<>(); int groupStart = -1, last = -1;
        for (int cross = min; cross <= max + 3; cross++) {
            int longest = 0, start = -1, end = -1;
            if (cross <= max) for (int along = from; along <= to + 3; along++) {
                if (along <= to && wall[index(!vertical, along, cross, width)]) { if (start < 0) start = along; end = along; }
                else if (start >= 0 && along - end > 2) { longest = Math.max(longest, end - start + 1); start = -1; }
            }
            // A projection count merges unrelated perpendicular walls and misses the true outer edge.
            if (longest >= Math.sqrt(expected) * .35) { if (groupStart < 0) groupStart = cross; last = cross; }
            else if (groupStart >= 0 && cross - last > 2) {
                positions.add(groupStart);
                if (last != groupStart) positions.add(last);
                groupStart = -1;
            }
        }
        return positions.stream().mapToInt(Integer::intValue).toArray();
    }

    private static boolean conflictsWithGrounded(int index, Region candidate, Map<Integer, Region> proposals,
        JsonNode rooms, Map<Integer, List<Double>> anchors, int width, int height) {
        var candidateShape = shape(candidate.ring());
        for (var other : proposals.entrySet()) {
            var region = other.getValue();
            if (other.getKey() == index || region.ring() == null
                || !ownsLabel(rooms, other.getKey(), normalized(region, width, height), anchors)) continue;
            var overlap = new Area(candidateShape); overlap.intersect(shape(region.ring()));
            if (!overlap.isEmpty()) return true;
        }
        return false;
    }

    private static double edgeSupport(boolean[] wall, boolean horizontal, int cross, int from, int to, int width, int height) {
        int count = 0, crossSize = horizontal ? height : width;
        for (int a = from; a <= to; a++) {
            boolean found = false;
            for (int d = -3; d <= 3 && !found; d++) if (cross + d >= 0 && cross + d < crossSize) found = wall[index(horizontal, a, cross + d, width)];
            if (found) count++;
        }
        return (double) count / (to - from + 1);
    }

    private static List<List<Double>> normalized(Region region, int width, int height) {
        return region.ring().stream().map(p -> List.of(p.get(0) * 1000 / width, p.get(1) * 1000 / height)).toList();
    }

    private static String labelKey(String text) {
        return RoomLabelGrounding.key(text);
    }

    /** Match available text occurrences one-to-one; dummy columns leave only missing occurrences unresolved. */
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
        for (var groupEntry : labels.entrySet()) {
            String name = groupEntry.getKey();
            var group = groupEntry.getValue();
            var indices = new ArrayList<Integer>();
            for (int i = 0; i < rooms.size(); i++) if (name.equals(labelKey(rooms.get(i).path("label").asText()))) indices.add(i);
            if (indices.isEmpty()) continue;
            if (indices.size() == 1 && group.size() == 1) { proposals.put(indices.get(0), group.get(0)); continue; }
            // A one-to-one minimum-cost assignment can recover translated candidates. Requiring
            // containment here made a bad initial polygon disable all wall correction for repeated names.
            int size = Math.max(indices.size(), group.size());
            double[][] costs = new double[size][size];
            for (int i = 0; i < indices.size(); i++) {
                // Invalid geometry may guide matching only; independent text is the actual flood seed.
                var candidate = points(rooms.get(indices.get(i)).path("polygon"), false);
                var box = rooms.get(indices.get(i)).path("labelBbox");
                double cx = Double.NaN, cy = Double.NaN;
                if (RoomLabelGrounding.validBox(box)) {
                    cx = (box.get(0).asDouble() + box.get(2).asDouble()) / 2;
                    cy = (box.get(1).asDouble() + box.get(3).asDouble()) / 2;
                } else if (candidate != null) {
                    var bounds = shape(candidate).getBounds2D(); cx = bounds.getCenterX(); cy = bounds.getCenterY();
                }
                for (int j = 0; j < size; j++) costs[i][j] = j >= group.size() ? 100000
                    : Double.isFinite(cx) ? Math.hypot(cx - group.get(j).get(0), cy - group.get(j).get(1)) : 30000;
            }
            int[] assignment = minimumAssignment(costs);
            for (int i = 0; i < indices.size(); i++) if (assignment[i] < group.size()) proposals.put(indices.get(i), group.get(assignment[i]));
        }
        Map<Integer, List<Double>> result = new HashMap<>();
        for (var entry : proposals.entrySet()) {
            String name = labelKey(rooms.get(entry.getKey()).path("label").asText());
            long uses = proposals.entrySet().stream().filter(other -> name.equals(labelKey(rooms.get(other.getKey()).path("label").asText())) && other.getValue().equals(entry.getValue())).count();
            if (uses == 1) result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /** Hungarian assignment; every independently detected text occurrence is used at most once. */
    private static int[] minimumAssignment(double[][] costs) {
        int n = costs.length;
        double[] u = new double[n + 1], v = new double[n + 1];
        int[] p = new int[n + 1], way = new int[n + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            double[] min = new double[n + 1]; Arrays.fill(min, Double.POSITIVE_INFINITY);
            boolean[] used = new boolean[n + 1]; int j0 = 0;
            do {
                used[j0] = true; int i0 = p[j0], j1 = 0; double delta = Double.POSITIVE_INFINITY;
                for (int j = 1; j <= n; j++) if (!used[j]) {
                    double cur = costs[i0 - 1][j - 1] - u[i0] - v[j];
                    if (cur < min[j]) { min[j] = cur; way[j] = j0; }
                    if (min[j] < delta) { delta = min[j]; j1 = j; }
                }
                for (int j = 0; j <= n; j++) { if (used[j]) { u[p[j]] += delta; v[j] -= delta; } else min[j] -= delta; }
                j0 = j1;
            } while (p[j0] != 0);
            do { int j1 = way[j0]; p[j0] = p[j1]; j0 = j1; } while (j0 != 0);
        }
        int[] result = new int[n];
        for (int j = 1; j <= n; j++) result[p[j] - 1] = j - 1;
        return result;
    }

    private static boolean eligible(JsonNode room, int index, boolean located, Map<Integer, List<Double>> anchors) {
        return room.path("label").isTextual() && !Set.of("OPEN_OFFICE", "COLLABORATION").contains(room.path("roomType").asText()) && (located ? anchors.containsKey(index) : ENCLOSED.contains(room.path("roomType").asText()));
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

    private static boolean[] wallMask(BufferedImage image) { return wallMask(image, false); }

    private static boolean[] wallMask(BufferedImage image, boolean weakFallback) {
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
        boolean pairedNetwork = restorePairedPartitions(thin, dark, wall, width, height);
        if (weakFallback) restoreConnectedThinRuns(thin, wall, width, height);
        strokes = wall.clone();
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
            if (pairedNetwork && !horizontal) continue;
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
        for (int y = pairedNetwork ? height : (int) (height * .65); y < height; y++) {
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
        int blankGap = pairedNetwork && !weakFallback ? Math.max(30, (int) Math.round(Math.max(width, height) * .04)) : maxGap;
        int support = Math.max(20, (int) Math.round(Math.max(width, height) * (weakFallback ? .04 : .055)));
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
                        if (previousStart >= 0 && start - previousEnd <= blankGap && (previousEnd - previousStart + 1 >= support || end - start + 1 >= support)) {
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

    /** Weaker lines are a second pass for unresolved rooms only; accepted structural contours stay intact. */
    private static void restoreConnectedThinRuns(boolean[] ink, boolean[] wall, int width, int height) {
        int span = Math.max(60, (int) Math.round(Math.max(width, height) * .055));
        for (boolean horizontal : new boolean[]{true, false, true, false}) {
            int alongSize = horizontal ? width : height, crossSize = horizontal ? height : width;
            for (int cross = 0; cross < crossSize; cross++) {
                int start = -1, last = -1;
                for (int along = 0; along <= alongSize + 2; along++) {
                    boolean stroke = along < alongSize && ink[index(horizontal, along, cross, width)];
                    if (stroke) { if (start < 0) start = along; last = along; }
                    else if (start >= 0 && along - last > 2) {
                        if (last - start + 1 >= span) {
                            boolean contact = false;
                            for (int a = start; a <= last && !contact; a += 5) contact = attached(wall, horizontal, a, cross, width, height, 5);
                            if (contact) for (int a = start; a <= last; a++) wall[index(horizontal, a, cross, width)] = true;
                        }
                        start = -1;
                    }
                }
            }
        }
    }

    private record PairedRun(boolean horizontal, int cross, int other, int start, int end) {}

    /** Thin double-line partitions belong to the same structural network as columns/walls.
     * Isolated desk/cabinet outlines must not become walls merely because they have parallel edges. */
    private static boolean restorePairedPartitions(boolean[] ink, boolean[] dark, boolean[] wall, int width, int height) {
        int span = Math.max(60, (int) Math.round(Math.max(width, height) * .055));
        int separation = Math.max(6, (int) Math.round(Math.max(width, height) * .009));
        List<PairedRun> runs = new ArrayList<>();
        for (boolean horizontal : new boolean[]{true, false}) {
            int alongSize = horizontal ? width : height, crossSize = horizontal ? height : width;
            for (int cross = 0; cross < crossSize; cross++) for (int gap = 3; gap <= separation && cross + gap < crossSize; gap++) {
                int start = -1, last = -1;
                for (int along = 0; along <= alongSize + 2; along++) {
                    boolean pair = along < alongSize && ink[index(horizontal, along, cross, width)] && ink[index(horizontal, along, cross + gap, width)];
                    if (pair) { if (start < 0) start = along; last = along; }
                    else if (start >= 0 && along - last > 2) {
                        if (last - start + 1 >= span) {
                            int empty = 0, total = (last - start + 1) * (gap - 1);
                            for (int a = start; a <= last; a++) for (int d = 1; d < gap; d++) if (!ink[index(horizontal, a, cross + d, width)]) empty++;
                            if (empty >= total * .25) runs.add(new PairedRun(horizontal, cross, cross + gap, start, last));
                        }
                        start = -1;
                    }
                }
            }
        }
        // A three-pixel desk edge is not a structural seed. Start at filled cores instead;
        // double-line furniture can otherwise attach to its own erroneously detected edge.
        boolean[] network = new boolean[wall.length];
        int[] sum = new int[(width + 1) * (height + 1)];
        for (int y = 0; y < height; y++) {
            int row = 0;
            for (int x = 0; x < width; x++) { row += dark[y * width + x] ? 1 : 0; sum[(y + 1) * (width + 1) + x + 1] = sum[y * (width + 1) + x + 1] + row; }
        }
        int core = Math.max(9, (int) Math.round(Math.max(width, height) * .006));
        for (int y = 0; y + core <= height; y++) for (int x = 0; x + core <= width; x++) {
            int filled = sum[(y + core) * (width + 1) + x + core] - sum[y * (width + 1) + x + core] - sum[(y + core) * (width + 1) + x] + sum[y * (width + 1) + x];
            if (filled == core * core) for (int dy = 0; dy < core; dy++) for (int dx = 0; dx < core; dx++) network[(y + dy) * width + x + dx] = true;
        }
        // Long, genuinely filled wall bands are structural too; reject short chair/text cores.
        for (boolean horizontal : new boolean[]{true, false}) {
            int alongSize = horizontal ? width : height, crossSize = horizontal ? height : width;
            for (int cross = 2; cross < crossSize - 2; cross++) {
                int start = -1;
                for (int along = 0; along <= alongSize; along++) {
                    boolean solid = along < alongSize;
                    for (int d = -2; d <= 2 && solid; d++) solid = dark[index(horizontal, along, cross + d, width)];
                    if (solid && start < 0) start = along;
                    if (!solid && start >= 0) {
                        if (along - start >= span) for (int a = start; a < along; a++) for (int d = -2; d <= 2; d++) network[index(horizontal, a, cross + d, width)] = true;
                        start = -1;
                    }
                }
            }
        }
        int seedPixels = 0; for (boolean pixel : network) if (pixel) seedPixels++;
        // Grow only from existing structural evidence. Doors are closed separately with bounded rules.
        boolean[] applied = new boolean[runs.size()];
        int radius = Math.max(3, (int) Math.round(Math.max(width, height) * .003));
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < runs.size(); i++) if (!applied[i]) {
                var r = runs.get(i);
                boolean connected = false;
                for (int a = r.start(); a <= r.end() && !connected; a += radius)
                    connected = attached(network, r.horizontal(), a, (r.cross() + r.other()) / 2, width, height, radius);
                if (!connected) continue;
                applied[i] = true; changed = true;
                for (int a = r.start(); a <= r.end(); a++) for (int c = r.cross(); c <= r.other(); c++) network[index(r.horizontal(), a, c, width)] = true;
            }
        } while (changed);
        int networkPixels = 0; for (boolean pixel : network) if (pixel) networkPixels++;
        long supported = 0;
        for (int i = 0; i < runs.size(); i++) if (applied[i]) supported += runs.get(i).end() - runs.get(i).start();
        // Switch only when thin partitions dominate the extracted network. Thick-wall drawings
        // keep their established mask and single-line glazing rules instead of losing those walls.
        if (supported > 2L * (width + height) && networkPixels > 2L * seedPixels) { System.arraycopy(network, 0, wall, 0, wall.length); return true; }
        return false;
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

    /** A text seed alone cannot justify replacing a small room with an entire connected core. */
    private static boolean consistentCandidate(List<List<Double>> candidate, List<List<Double>> ring) {
        double ratio = area(ring) / area(candidate);
        if (ratio < .25 || ratio > 2.5) return false;
        Area intersection = shape(ring); intersection.intersect(shape(candidate));
        var iterator = intersection.getPathIterator(null);
        double[] coordinate = new double[6];
        double sum = 0, startX = 0, startY = 0, previousX = 0, previousY = 0;
        while (!iterator.isDone()) {
            int segment = iterator.currentSegment(coordinate);
            if (segment == java.awt.geom.PathIterator.SEG_MOVETO) {
                startX = previousX = coordinate[0]; startY = previousY = coordinate[1];
            } else if (segment == java.awt.geom.PathIterator.SEG_LINETO) {
                sum += previousX * coordinate[1] - coordinate[0] * previousY;
                previousX = coordinate[0]; previousY = coordinate[1];
            } else if (segment == java.awt.geom.PathIterator.SEG_CLOSE) sum += previousX * startY - startX * previousY;
            iterator.next();
        }
        return Math.abs(sum) / 2 >= area(ring) * .55;
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
        return points(node, true);
    }
    private static List<List<Double>> points(JsonNode node, boolean requirePageBounds) {
        if (!node.isArray() || node.size() < 3 || node.size() > 256) return null;
        List<List<Double>> points = new ArrayList<>();
        for (var p : node) { if (!p.isArray() || p.size() != 2 || !p.get(0).isNumber() || !p.get(1).isNumber()) return null; double x = p.get(0).asDouble(), y = p.get(1).asDouble(); if (!Double.isFinite(x) || !Double.isFinite(y) || (requirePageBounds ? x < 0 || y < 0 || x > 1000 || y > 1000 : Math.abs(x) > 10000 || Math.abs(y) > 10000)) return null; points.add(List.of(x, y)); }
        return points;
    }
    private static boolean valid(List<List<Double>> points, SpatialGeometryService service) { var geometry = new SpatialGeometry(); geometry.setCoordinateSystem(SpatialGeometry.COORDINATE_SOURCE_PAGE); geometry.setOuter(points); return service.validate(geometry).isEmpty(); }
}
