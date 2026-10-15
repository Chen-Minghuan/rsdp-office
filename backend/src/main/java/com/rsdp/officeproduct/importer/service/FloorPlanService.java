package com.rsdp.officeproduct.importer.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.request.FloorPlanConfirmRequest;
import com.rsdp.officeproduct.importer.dto.response.FloorPlanAnalysisResponse;
import com.rsdp.officeproduct.importer.dto.response.FloorPlanBBox;
import com.rsdp.officeproduct.importer.dto.response.FloorPlanDrawingBounds;
import com.rsdp.officeproduct.importer.dto.response.FloorPlanPoint;
import com.rsdp.officeproduct.importer.dto.response.FloorPlanQualityIssue;
import com.rsdp.officeproduct.importer.dto.response.FloorPlanRoomResponse;
import com.rsdp.officeproduct.importer.entity.FloorPlanAnalysis;
import com.rsdp.officeproduct.importer.entity.FloorPlanRoom;
import com.rsdp.officeproduct.importer.entity.FloorPlanSceneRevision;
import com.rsdp.officeproduct.importer.entity.ImageAssets;
import com.rsdp.officeproduct.importer.mapper.FloorPlanAnalysisMapper;
import com.rsdp.officeproduct.importer.mapper.FloorPlanRoomMapper;
import com.rsdp.officeproduct.importer.mapper.FloorPlanSceneRevisionMapper;
import com.rsdp.officeproduct.importer.mapper.ImageAssetsMapper;
import com.rsdp.officeproduct.importer.office.entity.OfficeProject;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProjectMapper;
import com.rsdp.officeproduct.importer.util.CurrentUserContext;
import com.rsdp.officeproduct.importer.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 户型图 / 无限画布分析服务（从 RSDP 抽取并简化）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FloorPlanService {

    private static final String UPLOAD_DIR = "uploads/floor-plan";
    private static final List<String> IMAGE_TYPES = List.of("image/jpeg", "image/png", "image/webp", "image/jpg");
    private static final List<String> CAD_TYPES = List.of("application/octet-stream", "application/dwg", "application/dxf");

    private final FloorPlanAnalysisMapper analysisMapper;
    private final FloorPlanRoomMapper roomMapper;
    private final FloorPlanSceneRevisionMapper revisionMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final OfficeProjectMapper officeProjectMapper;
    private final CadParseClient cadParseClient;
    private final VisionRecognitionClient visionRecognitionClient;
    private final FloorPlanDimensionService floorPlanDimensionService;
    private final ObjectMapper objectMapper;
    private final com.rsdp.officeproduct.importer.service.spatial.SpatialGeometryService spatialGeometryService;

    // ==================== 分析任务 ====================

    /**
     * 上传户型图并创建分析任务（同步执行识别，不支持复杂异步编排）。
     */
    @Transactional
    public Map<String, String> analyze(MultipartFile image, MultipartFile cad, String hint,
                                       String projectId, String sourceName, String baseUrl) {
        return analyze(image, cad, hint, projectId, sourceName, baseUrl, 1);
    }

    @Transactional
    public Map<String, String> analyze(MultipartFile image, MultipartFile cad, String hint,
                                       String projectId, String sourceName, String baseUrl, int pageNumber) {
        if (!StringUtils.hasText(projectId)) {
            projectId = defaultProjectId();
        }
        validateProject(projectId);

        boolean hasImage = image != null && !image.isEmpty();
        boolean hasCad = cad != null && !cad.isEmpty();
        if (!hasImage && !hasCad) {
            throw new BusinessException("请上传户型图片或 CAD 图纸");
        }

        String inputMode = hasCad ? "cad" : (isPdf(image) ? "pdf" : "image");
        String analysisId = "FPA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // 保存源文件
        String imageId = hasImage ? saveImageAsset(image, baseUrl) : null;
        String cadObjectKey = hasCad ? saveCadFile(cad) : null;

        // 创建分析头表与 Revision
        FloorPlanAnalysis analysis = new FloorPlanAnalysis();
        analysis.setAnalysisId(analysisId);
        analysis.setProjectId(projectId);
        analysis.setSourceName(StringUtils.hasText(sourceName) ? sourceName.trim() : null);
        analysis.setStatus(FloorPlanAnalysis.STATUS_ANALYZING);
        analysis.setInputMode(inputMode);
        analysis.setImageId(imageId);
        analysis.setLatestAnalysisRevision(1);
        analysis.setCreatedBy(CurrentUserContext.currentUsername());
        analysis.setCreatedAt(LocalDateTime.now());
        analysis.setUpdatedAt(LocalDateTime.now());
        analysisMapper.insert(analysis);

        FloorPlanSceneRevision revision = new FloorPlanSceneRevision();
        revision.setAnalysisId(analysisId);
        revision.setAnalysisRevision(1);
        revision.setRevisionTrigger(FloorPlanSceneRevision.TRIGGER_INITIAL_PARSE);
        revision.setRevisionStatus(FloorPlanSceneRevision.STATUS_PROCESSING);
        revision.setRoomRevision(1);
        revision.setFurnitureRevision(1);
        revision.setCreatedBy(CurrentUserContext.currentUsername());
        revision.setCreatedAt(LocalDateTime.now());
        revisionMapper.insert(revision);

        // 调用识别服务
        JsonNode rawResult;
        try {
            if (hasCad) {
                rawResult = cadParseClient.parse(cad);
            } else {
                rawResult = visionRecognitionClient.recognize(image, pageNumber);
            }
        } catch (Exception e) {
            log.error("识别失败", e);
            analysis.setStatus(FloorPlanAnalysis.STATUS_FAILED);
            analysis.setErrorMessage(e.getMessage());
            analysis.setUpdatedAt(LocalDateTime.now());
            analysisMapper.updateById(analysis);

            revision.setRevisionStatus(FloorPlanSceneRevision.STATUS_FAILED);
            revisionMapper.updateByRevision(revision);
            return Map.of("analysisId", analysisId, "taskId", "TASK-" + analysisId);
        }

        // 解析结果并落库
        String geometrySource = hasCad ? FloorPlanAnalysis.GEOMETRY_SOURCE_CAD_GEOMETRY
            : FloorPlanAnalysis.GEOMETRY_SOURCE_AI_VISION;
        analysis.setGeometrySource(geometrySource);
        analysis.setRawResult(rawResult.toString());

        // 保存 CAD 规范预览图
        String previewImageId = extractAndSavePreview(rawResult, baseUrl);
        analysis.setPreviewImageId(previewImageId);
        revision.setPreviewImageId(previewImageId);

        // 提取边界
        FloorPlanDrawingBounds drawingBounds = extractDrawingBounds(rawResult);
        FloorPlanDrawingBounds previewBounds = extractPreviewBounds(rawResult, drawingBounds);
        revision.setDrawingBounds(writeJson(drawingBounds));
        revision.setPreviewBounds(writeJson(previewBounds));
        if (rawResult.has("unitResolution")) {
            revision.setUnitResolution(rawResult.get("unitResolution").toString());
        }

        // 提取质量提示
        List<FloorPlanQualityIssue> qualityIssues = extractQualityIssues(rawResult);
        revision.setQualityIssues(writeJson(qualityIssues));
        analysis.setQualityIssues(writeJson(qualityIssues));

        // 提取房间
        List<FloorPlanRoom> rooms = buildRooms(rawResult, analysisId, 1, geometrySource);
        for (FloorPlanRoom room : rooms) {
            roomMapper.insert(room);
        }

        // 完成
        analysis.setStatus(FloorPlanAnalysis.STATUS_AWAITING_CONFIRM);
        analysis.setWorkingAnalysisRevision(1);
        analysis.setUpdatedAt(LocalDateTime.now());
        analysisMapper.updateById(analysis);

        revision.setRevisionStatus(FloorPlanSceneRevision.STATUS_READY);
        revision.setParseStatus(extractParseStatus(rawResult));
        revision.setReadyAt(LocalDateTime.now());
        revision.setRawResult(rawResult.toString());
        revisionMapper.updateByRevision(revision);

        return Map.of("analysisId", analysisId, "taskId", "TASK-" + analysisId);
    }

    /**
     * 查询分析详情（默认装配 working ?? active ?? latest Revision）。
     */
    @Transactional(readOnly = true)
    public FloorPlanAnalysisResponse getAnalysis(String analysisId, Integer analysisRevision) {
        FloorPlanAnalysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null) {
            throw new BusinessException("分析批次不存在: " + analysisId);
        }

        Integer targetRevision = analysisRevision;
        if (targetRevision == null) {
            targetRevision = analysis.getWorkingAnalysisRevision() != null
                ? analysis.getWorkingAnalysisRevision()
                : (analysis.getActiveAnalysisRevision() != null
                    ? analysis.getActiveAnalysisRevision()
                    : analysis.getLatestAnalysisRevision());
        }
        if (targetRevision == null) {
            targetRevision = 1;
        }

        FloorPlanSceneRevision revision = revisionMapper.selectByRevision(analysisId, targetRevision);

        List<FloorPlanRoom> rooms = roomMapper.selectByAnalysisRevision(analysisId, targetRevision);

        FloorPlanAnalysisResponse response = new FloorPlanAnalysisResponse();
        response.setAnalysisId(analysisId);
        response.setProjectId(analysis.getProjectId());
        response.setProjectName(resolveProjectName(analysis.getProjectId()));
        response.setSourceName(analysis.getSourceName());
        response.setStatus(analysis.getStatus());
        response.setTaskId(analysis.getTaskId());
        response.setScaleRatio(analysis.getScaleRatio());
        response.setGeometrySource(analysis.getGeometrySource());
        response.setInputMode(analysis.getInputMode());
        response.setErrorMessage(analysis.getErrorMessage());
        response.setCreatedBy(analysis.getCreatedBy());
        response.setCreatedAt(analysis.getCreatedAt());
        response.setUpdatedAt(analysis.getUpdatedAt());
        response.setImageId(analysis.getImageId());
        response.setImageUrl(resolveImageUrl(analysis.getImageId()));
        response.setPreviewImageId(analysis.getPreviewImageId());
        response.setPreviewUrl(resolveImageUrl(analysis.getPreviewImageId()));
        response.setReferenceImageUrl(response.getImageUrl());
        response.setQualityIssues(parseQualityIssues(analysis.getQualityIssues()));
        response.setRooms(rooms.stream().map(this::toRoomResponse).toList());

        response.setAnalysisRevision(targetRevision);
        response.setActiveAnalysisRevision(analysis.getActiveAnalysisRevision());
        response.setWorkingAnalysisRevision(analysis.getWorkingAnalysisRevision());
        response.setLatestAnalysisRevision(analysis.getLatestAnalysisRevision());
        if (revision != null) {
            response.setRevisionStatus(revision.getRevisionStatus());
            response.setParseStatus(revision.getParseStatus());
            response.setRoomRevision(revision.getRoomRevision());
            response.setFurnitureRevision(revision.getFurnitureRevision());
            response.setDrawingBounds(parseBounds(revision.getDrawingBounds()));
            response.setPreviewBounds(parseBounds(revision.getPreviewBounds()));
            response.setUnitResolution(parseJsonNode(revision.getUnitResolution()));
            JsonNode original = parseJsonNode(revision.getRawResult());
            String assumedUnit = original == null ? "mm" : original.path("dimensionCalibrationConfirmation").path("assumedUnit").asText("mm");
            response.setDimensionCalibration(floorPlanDimensionService.report(original, analysisId, targetRevision, assumedUnit));
        }

        return response;
    }

    /**
     * 人工校正/确认空间列表（整体替换语义）。
     */
    @Transactional
    public FloorPlanAnalysisResponse confirmRooms(String analysisId, FloorPlanConfirmRequest request) {
        FloorPlanAnalysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null) {
            throw new BusinessException("分析批次不存在: " + analysisId);
        }

        Integer targetRevision = request.getAnalysisRevision() != null ? request.getAnalysisRevision()
            : analysis.getWorkingAnalysisRevision() != null ? analysis.getWorkingAnalysisRevision() : 1;

        FloorPlanSceneRevision revision = revisionMapper.selectByRevision(analysisId, targetRevision);
        if (revision == null) {
            throw new BusinessException("Revision 不存在: " + targetRevision);
        }

        // 软删当前 Revision 下所有房间，再按提交列表重建
        List<FloorPlanRoom> existing = roomMapper.selectByAnalysisRevision(analysisId, targetRevision);
        for (FloorPlanRoom room : existing) {
            roomMapper.deleteById(room.getRoomId());
        }

        int sortOrder = 0;
        for (FloorPlanConfirmRequest.RoomItem item : request.getRooms()) {
            FloorPlanRoom room = new FloorPlanRoom();
            room.setRoomId(StringUtils.hasText(item.getRoomId()) ? item.getRoomId() : "FPR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            room.setAnalysisId(analysisId);
            room.setAnalysisRevision(targetRevision);
            room.setRoomType(item.getRoomType());
            room.setLabel(item.getLabel());
            room.setWidthMm(item.getWidthMm());
            room.setDepthMm(item.getDepthMm());
            room.setPolygon(writeJson(item.getPolygon()));
            room.setBbox(writeJson(item.getBbox()));
            room.setAreaM2(calculateAreaFromPolygon(item.getPolygon(), item.getWidthMm(), item.getDepthMm()));
            room.setConfirmationStatus(FloorPlanRoom.CONFIRMATION_STATUS_CONFIRMED);
            room.setGeometrySource(FloorPlanAnalysis.GEOMETRY_SOURCE_MANUAL_GEOMETRY);
            room.setSortOrder(sortOrder++);
            room.setCreatedAt(LocalDateTime.now());
            room.setUpdatedAt(LocalDateTime.now());
            roomMapper.insert(room);
        }

        if (request.getScaleRatio() != null) {
            analysis.setScaleRatio(request.getScaleRatio());
        }
        analysis.setStatus(FloorPlanAnalysis.STATUS_CONFIRMED);
        analysis.setActiveAnalysisRevision(targetRevision);
        analysis.setWorkingAnalysisRevision(null);
        analysis.setUpdatedAt(LocalDateTime.now());
        analysisMapper.updateById(analysis);

        revision.setRevisionStatus(FloorPlanSceneRevision.STATUS_ACTIVE);
        revision.setConfirmedAt(LocalDateTime.now());
        revision.setRoomRevision(revision.getRoomRevision() + 1);
        revisionMapper.updateByRevision(revision);

        return getAnalysis(analysisId, targetRevision);
    }

    // ==================== 私有工具 ====================

    private String defaultProjectId() {
        return "OFFICE-PROJECT-001";
    }

    private void validateProject(String projectId) {
        OfficeProject project = officeProjectMapper.selectById(projectId);
        if (project == null) {
            throw new BusinessException("项目不存在: " + projectId);
        }
    }

    private boolean isPdf(MultipartFile file) {
        if (file == null) return false;
        String name = file.getOriginalFilename();
        return name != null && name.toLowerCase().endsWith(".pdf");
    }

    private String saveImageAsset(MultipartFile file, String baseUrl) {
        String imageId = IdGenerator.imageId();
        String ext = extension(file.getOriginalFilename());
        String filename = imageId + "." + ext;
        Path dir = Paths.get(System.getProperty("user.dir"), UPLOAD_DIR);
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir);
            file.transferTo(dir.resolve(filename));
        } catch (IOException e) {
            throw new BusinessException("保存户型图失败", e);
        }

        ImageAssets asset = new ImageAssets();
        asset.setImageId(imageId);
        asset.setImageType("floor_plan_source");
        asset.setStorageUrl(baseUrl + "/uploads/floor-plan/" + filename);
        asset.setFileSize(file.getSize());
        asset.setFormat(ext);
        asset.setUploadedBy(CurrentUserContext.currentUsername());
        asset.setCreatedAt(LocalDateTime.now());
        imageAssetsMapper.insert(asset);
        return imageId;
    }

    private String saveCadFile(MultipartFile file) {
        String key = "CAD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase() + ".dwg";
        Path dir = Paths.get(System.getProperty("user.dir"), UPLOAD_DIR, "cad");
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir);
            file.transferTo(dir.resolve(key));
        } catch (IOException e) {
            throw new BusinessException("保存 CAD 文件失败", e);
        }
        return key;
    }

    private String extension(String filename) {
        if (filename == null || !filename.contains(".")) return "jpg";
        String ext = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
        return List.of("jpg", "jpeg", "png", "webp", "pdf", "dwg", "dxf").contains(ext) ? ext : "jpg";
    }

    private String extractAndSavePreview(JsonNode raw, String baseUrl) {
        JsonNode preview = raw.path("preview");
        if (preview.isMissingNode() || !preview.has("pngBase64")) {
            return null;
        }
        String base64 = preview.get("pngBase64").asText();
        if (!StringUtils.hasText(base64)) return null;

        String imageId = IdGenerator.imageId();
        String filename = imageId + ".png";
        Path dir = Paths.get(System.getProperty("user.dir"), UPLOAD_DIR);
        try {
            if (!Files.exists(dir)) Files.createDirectories(dir);
            byte[] bytes = Base64.getDecoder().decode(base64);
            Files.write(dir.resolve(filename), bytes);
        } catch (Exception e) {
            log.warn("保存 CAD 预览图失败", e);
            return null;
        }

        ImageAssets asset = new ImageAssets();
        asset.setImageId(imageId);
        asset.setImageType("floor_plan_preview");
        asset.setStorageUrl(baseUrl + "/uploads/floor-plan/" + filename);
        asset.setFormat("png");
        asset.setUploadedBy(CurrentUserContext.currentUsername());
        asset.setCreatedAt(LocalDateTime.now());
        imageAssetsMapper.insert(asset);
        return imageId;
    }

    private FloorPlanDrawingBounds extractDrawingBounds(JsonNode raw) {
        JsonNode bounds = raw.path("drawingBounds");
        if (bounds.isMissingNode()) {
            bounds = raw.path("preview").path("bounds");
        }
        return parseBoundsNode(bounds);
    }

    private FloorPlanDrawingBounds extractPreviewBounds(JsonNode raw, FloorPlanDrawingBounds fallback) {
        JsonNode bounds = raw.path("preview").path("bounds");
        if (bounds.isMissingNode()) {
            return fallback;
        }
        FloorPlanDrawingBounds result = parseBoundsNode(bounds);
        return result != null ? result : fallback;
    }

    private FloorPlanDrawingBounds parseBoundsNode(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.has("minX")) return null;
        FloorPlanDrawingBounds b = new FloorPlanDrawingBounds();
        b.setMinX(node.get("minX").asDouble());
        b.setMinY(node.get("minY").asDouble());
        b.setMaxX(node.get("maxX").asDouble());
        b.setMaxY(node.get("maxY").asDouble());
        return b;
    }

    private List<FloorPlanQualityIssue> extractQualityIssues(JsonNode raw) {
        List<FloorPlanQualityIssue> list = new ArrayList<>();
        JsonNode issues = raw.path("qualityIssues");
        if (issues.isMissingNode() || !issues.isArray()) return list;
        for (JsonNode item : issues) {
            FloorPlanQualityIssue issue = new FloorPlanQualityIssue();
            issue.setLevel(item.path("level").asText("warn"));
            issue.setCode(item.path("code").asText(""));
            issue.setMessage(item.path("message").asText(""));
            list.add(issue);
        }
        return list;
    }

    private String extractParseStatus(JsonNode raw) {
        if (raw.has("parseStatus")) return raw.get("parseStatus").asText("SUCCESS");
        if (raw.path("success").asBoolean(true)) return "SUCCESS";
        return "FAILED";
    }

    private List<FloorPlanRoom> buildRooms(JsonNode raw, String analysisId, Integer analysisRevision, String geometrySource) {
        List<FloorPlanRoom> result = new ArrayList<>();
        int order = 0;

        JsonNode roomsNode = raw.path("rooms");
        if (roomsNode.isArray()) {
            for (JsonNode node : roomsNode) {
                FloorPlanRoom room = buildRoom(node, analysisId, analysisRevision, geometrySource, order++, false);
                if (room != null) result.add(room);
            }
        }

        JsonNode unnamedNode = raw.path("unnamedRegions");
        if (unnamedNode.isArray()) {
            for (JsonNode node : unnamedNode) {
                FloorPlanRoom room = buildRoom(node, analysisId, analysisRevision, geometrySource, order++, true);
                if (room != null) result.add(room);
            }
        }

        return result;
    }

    private FloorPlanRoom buildRoom(JsonNode node, String analysisId, Integer analysisRevision,
                                    String geometrySource, int sortOrder, boolean unnamed) {
        if (node == null || node.isMissingNode()) return null;
        FloorPlanRoom room = new FloorPlanRoom();
        room.setRoomId("FPR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        room.setAnalysisId(analysisId);
        room.setAnalysisRevision(analysisRevision);
        room.setRoomType(node.path("roomType").asText("OTHER"));
        room.setLabel(StringUtils.hasText(node.path("label").asText(null))
            ? node.path("label").asText()
            : (unnamed ? "未命名空间" : null));
        room.setWidthMm(asInt(node.path("widthMm")));
        room.setDepthMm(asInt(node.path("depthMm")));
        room.setAreaM2(asBigDecimal(node.path("areaM2")));
        room.setDimensionSource(node.path("dimensionSource").asText(geometrySource.equals(FloorPlanAnalysis.GEOMETRY_SOURCE_CAD_GEOMETRY) ? "cad_geometry" : "ai_estimate"));
        room.setDimensionConfidence(node.path("confidence").asText("mid"));
        room.setDimensionText(node.path("dimensionText").asText(null));
        room.setGeometrySource(geometrySource);
        room.setConfirmationStatus(FloorPlanRoom.CONFIRMATION_STATUS_AUTO);
        room.setPolygon(writeJson(extractPolygon(node.path("polygon"))));
        room.setCentroid(writeJson(extractPoint(node.path("centroid"))));
        room.setLabelPoint(writeJson(extractPoint(node.path("labelPoint"))));
        room.setBbox(writeJson(extractBBox(node.path("bbox"))));
        room.setSortOrder(sortOrder);
        room.setCreatedAt(LocalDateTime.now());
        room.setUpdatedAt(LocalDateTime.now());
        return room;
    }

    private List<List<Double>> extractPolygon(JsonNode polygonNode) {
        if (polygonNode.isMissingNode() || !polygonNode.isArray()) return null;
        List<List<Double>> polygon = new ArrayList<>();
        for (JsonNode point : polygonNode) {
            if (point.isArray() && point.size() >= 2) {
                polygon.add(List.of(point.get(0).asDouble(), point.get(1).asDouble()));
            }
        }
        return polygon.isEmpty() ? null : polygon;
    }

    private FloorPlanPoint extractPoint(JsonNode node) {
        if (node.isMissingNode() || !node.has("x")) return null;
        FloorPlanPoint p = new FloorPlanPoint();
        p.setX(node.get("x").asDouble());
        p.setY(node.get("y").asDouble());
        return p;
    }

    private FloorPlanBBox extractBBox(JsonNode node) {
        if (node.isMissingNode() || !node.has("minX")) return null;
        FloorPlanBBox b = new FloorPlanBBox();
        b.setX(node.get("minX").asDouble());
        b.setY(node.get("minY").asDouble());
        b.setW(node.has("maxX") ? node.get("maxX").asDouble() - b.getX() : 0);
        b.setH(node.has("maxY") ? node.get("maxY").asDouble() - b.getY() : 0);
        return b;
    }

    private FloorPlanRoomResponse toRoomResponse(FloorPlanRoom room) {
        FloorPlanRoomResponse response = new FloorPlanRoomResponse();
        response.setRoomId(room.getRoomId());
        response.setRoomType(room.getRoomType());
        response.setLabel(room.getLabel());
        response.setPolygon(parsePolygon(room.getPolygon()));
        response.setLabelPoint(parsePoint(room.getLabelPoint()));
        response.setBbox(parseBBox(room.getBbox()));
        response.setWidthMm(room.getWidthMm());
        response.setDepthMm(room.getDepthMm());
        response.setAreaM2(room.getAreaM2());
        response.setDimensionSource(room.getDimensionSource());
        response.setDimensionConfidence(room.getDimensionConfidence());
        response.setDimensionText(room.getDimensionText());
        response.setSortOrder(room.getSortOrder());
        response.setConfirmationStatus(room.getConfirmationStatus());
        response.setGeometrySource(room.getGeometrySource());
        return response;
    }

    private String resolveProjectName(String projectId) {
        if (!StringUtils.hasText(projectId)) return null;
        OfficeProject project = officeProjectMapper.selectById(projectId);
        return project != null ? project.getProjectName() : null;
    }

    private String resolveImageUrl(String imageId) {
        if (!StringUtils.hasText(imageId)) return null;
        ImageAssets asset = imageAssetsMapper.selectById(imageId);
        return asset != null ? asset.getStorageUrl() : null;
    }

    private Integer asInt(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        return node.asInt();
    }

    private BigDecimal asBigDecimal(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        return BigDecimal.valueOf(node.asDouble()).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateArea(Integer widthMm, Integer depthMm) {
        if (widthMm == null || depthMm == null) return null;
        return BigDecimal.valueOf(widthMm).multiply(BigDecimal.valueOf(depthMm))
            .divide(BigDecimal.valueOf(1_000_000), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateAreaFromPolygon(List<List<Double>> polygon, Integer widthMm, Integer depthMm) {
        if (polygon != null && !polygon.isEmpty() && spatialGeometryService != null) {
            try {
                double areaMm2 = Math.abs(spatialGeometryService.ringArea(polygon));
                if (areaMm2 > 0) {
                    return BigDecimal.valueOf(areaMm2 / 1_000_000.0).setScale(4, RoundingMode.HALF_UP);
                }
            } catch (Exception e) {
                log.warn("按多边形计算面积失败，回退到宽×深", e);
            }
        }
        return calculateArea(widthMm, depthMm);
    }

    private String writeJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("JSON 序列化失败", e);
            return null;
        }
    }

    private JsonNode parseJsonNode(String json) {
        if (!StringUtils.hasText(json)) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private List<List<Double>> parsePolygon(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Double.class)));
        } catch (Exception e) {
            return null;
        }
    }

    private FloorPlanPoint parsePoint(String json) {
        if (!StringUtils.hasText(json)) return null;
        try {
            return objectMapper.readValue(json, FloorPlanPoint.class);
        } catch (Exception e) {
            return null;
        }
    }

    private FloorPlanBBox parseBBox(String json) {
        if (!StringUtils.hasText(json)) return null;
        try {
            return objectMapper.readValue(json, FloorPlanBBox.class);
        } catch (Exception e) {
            return null;
        }
    }

    private FloorPlanDrawingBounds parseBounds(String json) {
        if (!StringUtils.hasText(json)) return null;
        try {
            return objectMapper.readValue(json, FloorPlanDrawingBounds.class);
        } catch (Exception e) {
            return null;
        }
    }

    private List<FloorPlanQualityIssue> parseQualityIssues(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, FloorPlanQualityIssue.class));
        } catch (Exception e) {
            return List.of();
        }
    }
}
