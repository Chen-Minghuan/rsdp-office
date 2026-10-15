package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.request.SpaceTemplateCreateRequest;
import com.rsdp.officeproduct.importer.dto.response.SpaceTemplateResponse;
import com.rsdp.officeproduct.importer.dto.spatial.*;
import com.rsdp.officeproduct.importer.entity.SpaceTemplate;
import com.rsdp.officeproduct.importer.mapper.SpaceTemplateMapper;
import com.rsdp.officeproduct.importer.office.service.OfficeCanvasReviewPolicy;
import com.rsdp.officeproduct.importer.service.spatial.SpatialGeometryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/** Immutable, project-scoped design snapshots. Recognition and the editable canvas are never updated here. */
@Service
@RequiredArgsConstructor
public class SpaceTemplateService {
    private final SpaceTemplateMapper mapper;
    private final SpatialGeometryService geometry;
    private final ObjectMapper json;

    @Transactional
    public SpaceTemplateResponse create(String projectId, SpaceTemplateCreateRequest request) {
        project(projectId);
        if (request == null || request.getScene() == null || request.getName() == null || request.getName().isBlank() || request.getName().length() > 120 ||
            request.getClientChangeId() == null || request.getClientChangeId().isBlank() || request.getClientChangeId().length() > 64 ||
            request.getSourceCanvasRevision() == null || request.getSourceCanvasRevision() < 0) throw new BusinessException("空间模板参数不完整");
        SpatialSceneSnapshot scene = json.convertValue(request.getScene(), SpatialSceneSnapshot.class);
        if (!"WORLD_MM".equals(scene.getWorkingCoordinateSystem()) || scene.getCalibration() == null || !"CONFIRMED".equals(scene.getCalibration().getStatus())) throw new BusinessException("请先校准实际比例，再生成空间模板");
        var matrix = scene.getCalibration().getSourceToWorld();
        if (matrix == null || matrix.size() != 6 || matrix.stream().anyMatch(value -> value == null || !Double.isFinite(value)) ||
            !Double.isFinite(matrix.get(0) * matrix.get(3) - matrix.get(1) * matrix.get(2)) || Math.abs(matrix.get(0) * matrix.get(3) - matrix.get(1) * matrix.get(2)) < 1e-12) throw new BusinessException("图纸比例变换无效");
        if (scene.getSpaces() == null || scene.getSpaces().isEmpty() || scene.getSpaces().size() > 500) throw new BusinessException("空间模板需要 1 至 500 个有效空间");
        scene.setProjectId(projectId);
        scene.setConfirmedSceneId(null);
        if (scene.getWalls() == null) scene.setWalls(List.of());
        if (scene.getOpenings() == null) scene.setOpenings(List.of());
        if (scene.getObstacles() == null) scene.setObstacles(List.of());
        if (scene.getQualityIssues() == null) scene.setQualityIssues(List.of());
        List<Map<String,Object>> rooms = new ArrayList<>();
        for (var space : scene.getSpaces()) {
            if (space == null || space.getSpaceId() == null || space.getSpaceId().isBlank() || space.getSpaceId().length() > 128 ||
                space.getName() == null || space.getName().isBlank() || space.getName().length() > 200) throw new BusinessException("空间缺少有效名称或 ID");
            validateGeometry(space.getDesignGeometry(), space.getSpaceId());
            rooms.add(Map.of("id", space.getSpaceId(), "kind", "zone", "reviewUnit", "mm", "polygon", space.getDesignGeometry().getOuter()));
            space.setRectangleFrame(geometry.inferRectangleFrame(space.getDesignGeometry()));
            SpatialDerived derived = geometry.derive(space.getDesignGeometry(), null);
            Area usable = footprint(space.getDesignGeometry());
            Area obstacles = new Area();
            for (var obstacle : scene.getObstacles()) {
                if (obstacle == null) throw new BusinessException("障碍数据无效");
                if (obstacle.getSpaceId() != null && !obstacle.getSpaceId().equals(space.getSpaceId())) continue;
                if (Boolean.TRUE.equals(obstacle.getExcludesFloorArea())) {
                    validateGeometry(obstacle.getGeometry(), "障碍");
                    obstacles.add(footprint(obstacle.getGeometry()));
                }
            }
            usable.subtract(obstacles);
            derived.setAreaM2(geometry.toM2(area(usable)));
            space.setDerived(derived);
            SpatialSpaceConfirmation confirmation = new SpatialSpaceConfirmation();
            confirmation.setStatus("UNCONFIRMED");
            space.setConfirmation(confirmation);
        }
        // Confirmed flags and overlaps are intentionally not admission conditions.
        OfficeCanvasReviewPolicy.requireLayoutReady(json.valueToTree(rooms), scene.getSpaces().get(0).getSpaceId());
        String name = request.getName().trim();
        var content = new TreeMap<String,Object>(Map.of("name", name, "sourceCanvasRevision", request.getSourceCanvasRevision(), "scene", scene));
        content.put("sourceAnalysisRevision", request.getSourceAnalysisRevision());
        String original = encode(content);
        if (original.length() > 4_000_000) throw new BusinessException("空间模板过大，请减少无关数据");
        String hash = VisionImagePreparer.sha256(original.getBytes(StandardCharsets.UTF_8));
        SpaceTemplate previous = mapper.byChange(projectId, request.getClientChangeId());
        if (previous != null) return repeat(previous, hash);
        SpaceTemplate entry = new SpaceTemplate();
        entry.setTemplateId("STPL-" + UUID.randomUUID());
        entry.setProjectId(projectId); entry.setName(name); entry.setClientChangeId(request.getClientChangeId());
        entry.setContentHash(hash); entry.setSourceCanvasRevision(request.getSourceCanvasRevision());
        entry.setSourceAnalysisId(scene.getSourceSceneId()); entry.setSourceAnalysisRevision(request.getSourceAnalysisRevision()); entry.setSpaceCount(scene.getSpaces().size());
        entry.setAreaM2(scene.getSpaces().stream().map(space -> space.getDerived().getAreaM2()).reduce(BigDecimal.ZERO, BigDecimal::add));
        entry.setEvidenceKind(scene.getSpaces().stream().anyMatch(space -> "DEMO".equals(space.getEvidenceKind())) ? "DEMO" :
            scene.getSpaces().stream().anyMatch(space -> "ESTIMATED".equals(space.getEvidenceKind()) || space.getEvidenceKind() == null) ? "ESTIMATED" : scene.getSpaces().stream().anyMatch(space -> "REAL_SERVICE".equals(space.getEvidenceKind())) ? "REAL_SERVICE" : "MANUAL");
        entry.setCreatedAt(LocalDateTime.now());
        scene.setSceneId(entry.getTemplateId());
        entry.setSceneJson(encode(scene));
        if (mapper.insert(entry) != 1) return repeat(mapper.byChange(projectId, request.getClientChangeId()), hash);
        return response(entry);
    }

    public List<SpaceTemplateResponse> list(String projectId) {
        project(projectId);
        return mapper.list(projectId).stream().map(this::response).toList();
    }
    public SpaceTemplateResponse get(String projectId, String templateId) {
        project(projectId);
        SpaceTemplate entry = mapper.get(projectId, templateId);
        if (entry == null) throw new BusinessException("空间模板不存在或不属于当前项目");
        return response(entry);
    }
    private SpaceTemplateResponse repeat(SpaceTemplate entry, String hash) {
        if (entry == null || !hash.equals(entry.getContentHash())) throw new BusinessException("同一保存请求对应不同内容，请重新生成空间模板");
        return response(entry);
    }
    private void project(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}")) throw new BusinessException("项目 ID 无效");
    }
    private void validateGeometry(SpatialGeometry shape, String id) {
        if (shape == null || !"WORLD_MM".equals(shape.getCoordinateSystem()) || !"POLYGON".equals(shape.getType()) || shape.getOuter() == null || shape.getOuter().size() > 2000) throw new BusinessException(id + "：需要有效毫米多边形");
        OfficeCanvasReviewPolicy.requireLayoutReady(json.valueToTree(List.of(Map.of("id", id, "kind", "zone", "reviewUnit", "mm", "polygon", shape.getOuter()))), id);
        if (shape.getOuterVertexIds() == null || shape.getOuterVertexIds().size() != shape.getOuter().size()) shape.setOuterVertexIds(ids(id, shape.getOuter().size()));
        if (new HashSet<>(shape.getOuterVertexIds()).size() != shape.getOuterVertexIds().size() || shape.getOuterVertexIds().stream().anyMatch(idValue -> idValue == null || idValue.isBlank())) throw new BusinessException("空间顶点 ID 无效");
        if (!Double.isFinite(geometry.ringArea(shape.getOuter()))) throw new BusinessException("空间面积超出可计算范围");
        if (shape.getHoles() == null) shape.setHoles(List.of());
        if (shape.getHoles().size() > 100) throw new BusinessException("孔洞数量过多");
        Area outside = polygon(shape.getOuter()), used = new Area();
        for (var hole : shape.getHoles()) {
            if (hole == null || hole.size() > 2000) throw new BusinessException("孔洞轮廓无效");
            OfficeCanvasReviewPolicy.requireLayoutReady(json.valueToTree(List.of(Map.of("id", id, "kind", "zone", "reviewUnit", "mm", "polygon", hole))), id);
            Area cutout = polygon(hole), test = new Area(cutout); test.subtract(outside);
            if (!test.isEmpty()) throw new BusinessException("孔洞必须位于所属空间内");
            test = new Area(cutout); test.intersect(used);
            if (!test.isEmpty()) throw new BusinessException("孔洞不能互相重叠");
            used.add(cutout);
        }
        if (area(footprint(shape)) <= 1) throw new BusinessException("扣除孔洞后空间面积无效");
    }
    private List<String> ids(String id, int count) {
        List<String> result = new ArrayList<>(); for (int i=0;i<count;i++) result.add(id + "-V-" + i); return result;
    }
    private Area footprint(SpatialGeometry shape) {
        Area result = polygon(shape.getOuter());
        for (var hole : shape.getHoles()) result.subtract(polygon(hole));
        return result;
    }
    private Area polygon(List<List<Double>> points) {
        Path2D.Double path = new Path2D.Double();
        for (int i=0;i<points.size();i++) { var p=points.get(i); if (i==0) path.moveTo(p.get(0),p.get(1)); else path.lineTo(p.get(0),p.get(1)); }
        path.closePath(); return new Area(path);
    }
    private double area(Area shape) {
        double sum=0, firstX=0, firstY=0, x=0, y=0; double[] point=new double[6];
        for (PathIterator it=shape.getPathIterator(null);!it.isDone();it.next()) {
            int type=it.currentSegment(point);
            if (type==PathIterator.SEG_MOVETO) { firstX=x=point[0]; firstY=y=point[1]; }
            else if (type==PathIterator.SEG_LINETO) { sum+=x*point[1]-point[0]*y; x=point[0]; y=point[1]; }
            else if (type==PathIterator.SEG_CLOSE) sum+=x*firstY-firstX*y;
        }
        return Math.abs(sum)/2;
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new BusinessException("空间模板数据无法保存"); }
    }
    private SpaceTemplateResponse response(SpaceTemplate entry) {
        var result=json.convertValue(entry, com.fasterxml.jackson.databind.node.ObjectNode.class);
        result.remove(List.of("sceneJson", "clientChangeId"));
        SpaceTemplateResponse response=json.convertValue(result, SpaceTemplateResponse.class);
        if (entry.getSceneJson()!=null) try { response.setScene(json.readValue(entry.getSceneJson(),SpatialSceneSnapshot.class)); }
        catch (JsonProcessingException e) { throw new BusinessException("空间模板数据不可读取"); }
        return response;
    }
}
