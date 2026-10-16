package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.dto.response.DesignContourCorrection;
import com.rsdp.officeproduct.importer.dto.spatial.SpatialGeometry;
import com.rsdp.officeproduct.importer.entity.FloorPlanAnalysis;
import com.rsdp.officeproduct.importer.entity.FloorPlanSceneRevision;
import com.rsdp.officeproduct.importer.mapper.FloorPlanAnalysisMapper;
import com.rsdp.officeproduct.importer.mapper.FloorPlanSceneRevisionMapper;
import com.rsdp.officeproduct.importer.office.entity.OfficeCanvas;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCanvasMapper;
import com.rsdp.officeproduct.importer.service.spatial.SpatialGeometryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

/** Reads design corrections separately from immutable recognition. Never changes source rooms or scale. */
@Service
@RequiredArgsConstructor
public class FloorPlanDesignCorrectionService {
    private final OfficeCanvasMapper canvasMapper;
    private final FloorPlanAnalysisMapper analysisMapper;
    private final FloorPlanSceneRevisionMapper revisionMapper;
    private final ObjectMapper mapper;
    private final SpatialGeometryService geometry;

    public List<DesignContourCorrection> find(String projectId, String imageSha256) {
        if (projectId == null || imageSha256 == null || !imageSha256.matches("[a-fA-F0-9]{64}")) return List.of();
        var snapshots = canvasMapper.selectCorrectionSnapshots(projectId, imageSha256);
        Map<String, JsonNode> sources = new HashMap<>();
        Map<String, DesignContourCorrection> corrections = new LinkedHashMap<>();
        for (var snapshot : snapshots) {
            JsonNode objects = json(snapshot.getObjectsJson());
            if (!objects.isArray() || snapshot.getRevision() == null) continue;
            Map<String, Integer> names = new HashMap<>();
            for (var zone : objects) if ("zone".equals(zone.path("kind").asText())) names.merge(key(zone.path("name").asText()), 1, Integer::sum);
            for (var zone : objects) {
                String name = zone.path("name").asText(), key = key(name);
                if (!"zone".equals(zone.path("kind").asText()) || !"manual_geometry".equals(zone.path("geometrySource").asText())
                    || key.isEmpty() || corrections.containsKey(key) || names.getOrDefault(key, 0) != 1
                    || "DEMO".equals(zone.path("evidenceKind").asText()) || "up".equals(zone.path("sourceYAxis").asText())) continue;
                String sourceId = zone.path("sourceAnalysisId").asText();
                int revision = zone.path("sourceAnalysisRevision").asInt(1);
                JsonNode source = sources.computeIfAbsent(sourceId + ":" + revision, ignored -> source(projectId, sourceId, revision));
                if (!imageSha256.equals(source.path("imageSha256").asText()) || !"px".equals(source.path("units").asText())) continue;
                double scale = switch (zone.path("reviewUnit").asText()) {
                    case "source" -> 1;
                    case "mm" -> zone.path("mmPerSourceUnit").asDouble(Double.NaN);
                    default -> Double.NaN;
                };
                if (!Double.isFinite(scale) || scale <= 0) continue;
                List<List<Double>> ring = new ArrayList<>();
                boolean valid = zone.path("polygon").isArray();
                for (var point : zone.path("polygon")) {
                    if (!point.isArray() || point.size() != 2 || !point.get(0).isNumber() || !point.get(1).isNumber()) { valid = false; break; }
                    double x = point.get(0).asDouble() / scale, y = point.get(1).asDouble() / scale;
                    if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0
                        || x > source.path("preview").path("width").asDouble() || y > source.path("preview").path("height").asDouble()) { valid = false; break; }
                    ring.add(List.of(x, y));
                }
                if (!valid || ring.size() < 3 || ring.size() > 256) continue;
                var shape = new SpatialGeometry(); shape.setCoordinateSystem(SpatialGeometry.COORDINATE_SOURCE_PAGE); shape.setOuter(ring);
                if (!geometry.validate(shape).isEmpty()) continue;
                corrections.put(key, new DesignContourCorrection(name, ring, sourceId, revision, snapshot.getRevision()));
            }
        }
        return List.copyOf(corrections.values());
    }

    private JsonNode source(String projectId, String analysisId, int revision) {
        FloorPlanAnalysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null || !projectId.equals(analysis.getProjectId())) return mapper.createObjectNode();
        FloorPlanSceneRevision source = revisionMapper.selectByRevision(analysisId, revision);
        return source == null ? mapper.createObjectNode() : json(source.getRawResult());
    }

    private JsonNode json(String text) {
        try { JsonNode result = text == null ? null : mapper.readTree(text); return result == null ? mapper.createObjectNode() : result; }
        catch (java.io.IOException error) { return mapper.createObjectNode(); }
    }

    private static String key(String name) { return name.replaceAll("\\s+", ""); }
}
