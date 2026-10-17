package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/** Text boxes are evidence of label position only, never room boundaries. */
final class RoomLabelGrounding {
    private RoomLabelGrounding() {}

    static String key(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).replaceAll("\\s+", "");
    }

    static boolean validBox(JsonNode box) {
        if (!box.isArray() || box.size() != 4) return false;
        for (var v : box) if (!v.isNumber() || !Double.isFinite(v.asDouble()) || v.asDouble() < 0 || v.asDouble() > 1000) return false;
        return box.get(2).asDouble() > box.get(0).asDouble() && box.get(3).asDouble() > box.get(1).asDouble()
            && box.get(2).asDouble() - box.get(0).asDouble() <= 200 && box.get(3).asDouble() - box.get(1).asDouble() <= 100;
    }

    static double distance(JsonNode a, JsonNode b) {
        return Math.hypot((a.get(0).asDouble() + a.get(2).asDouble() - b.get(0).asDouble() - b.get(2).asDouble()) / 2,
            (a.get(1).asDouble() + a.get(3).asDouble() - b.get(1).asDouble() - b.get(3).asDouble()) / 2);
    }

    static ObjectNode merge(JsonNode primary, ObjectNode independent, ObjectMapper mapper) {
        ObjectNode merged = independent == null ? mapper.createObjectNode().put("status", "DISABLED") : independent.deepCopy();
        merged.put("coordinateSystem", "NORMALIZED_1000");
        merged.put("independentStatus", merged.path("status").asText("DISABLED"));
        var labels = merged.putArray("labels");
        if (independent != null && "DETECTED".equals(independent.path("status").asText())) {
            for (var label : independent.path("labels")) {
                if (!label.isObject() || key(label.path("text").asText("")).isEmpty() || !validBox(label.path("bbox"))) continue;
                boolean duplicate = false;
                for (var existing : labels) if (key(existing.path("text").asText()).equals(key(label.path("text").asText()))
                    && distance(existing.path("bbox"), label.path("bbox")) < 2) duplicate = true;
                if (!duplicate && labels.size() < 200) {
                    ObjectNode copy = label.deepCopy();
                    copy.put("source", "INDEPENDENT_LABEL_DETECTION"); labels.add(copy);
                }
            }
        }
        List<JsonNode> primaryLabels = new ArrayList<>();
        for (var label : primary.path("labels")) if (label.isObject()
            && !key(label.path("text").asText("")).isEmpty() && validBox(label.path("bbox"))) {
            boolean duplicate = primaryLabels.stream().anyMatch(other -> key(other.path("text").asText()).equals(key(label.path("text").asText()))
                && distance(other.path("bbox"), label.path("bbox")) < 2);
            if (!duplicate) primaryLabels.add(label);
        }
        // Room bounds describe geometry, never text. Only explicit labelBbox can seed grounding.
        for (var room : primary.path("rooms")) if (room.isObject()
            && !key(room.path("label").asText("")).isEmpty() && validBox(room.path("labelBbox"))) {
            var label = mapper.createObjectNode().put("text", room.path("label").asText());
            label.set("bbox", room.path("labelBbox").deepCopy());
            boolean duplicate = primaryLabels.stream().anyMatch(other -> key(other.path("text").asText()).equals(key(label.path("text").asText()))
                && distance(other.path("bbox"), label.path("bbox")) < 2);
            if (!duplicate) primaryLabels.add(label);
        }
        int fallback = 0;
        for (var label : primaryLabels) {
            String name = key(label.path("text").asText());
            long expected = primaryLabels.stream().filter(other -> name.equals(key(other.path("text").asText()))).count();
            int found = 0; boolean nearby = false;
            for (var existing : labels) if (name.equals(key(existing.path("text").asText()))) {
                found++;
                if (distance(existing.path("bbox"), label.path("bbox")) < 20) nearby = true;
            }
            // Independent detections win; only fill missing occurrences, not translated duplicates.
            if (found >= expected || nearby || labels.size() >= 200) continue;
            ObjectNode copy = label.deepCopy();
            copy.put("source", "PRIMARY_SEMANTIC_LABEL"); labels.add(copy); fallback++;
        }
        if (!labels.isEmpty()) merged.put("status", "DETECTED");
        merged.put("primaryFallbackCount", fallback);
        return merged;
    }
}
