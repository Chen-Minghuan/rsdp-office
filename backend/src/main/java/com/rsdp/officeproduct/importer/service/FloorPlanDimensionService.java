package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.response.DimensionCalibrationReport;
import com.rsdp.officeproduct.importer.entity.FloorPlanAnalysis;
import com.rsdp.officeproduct.importer.entity.FloorPlanSceneRevision;
import com.rsdp.officeproduct.importer.mapper.FloorPlanAnalysisMapper;
import com.rsdp.officeproduct.importer.mapper.FloorPlanSceneRevisionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Adds dimension evidence to a revision without replacing rooms, units or edited design geometry. */
@Service
@RequiredArgsConstructor
public class FloorPlanDimensionService {
    private final FloorPlanAnalysisMapper analysisMapper;
    private final FloorPlanSceneRevisionMapper revisionMapper;
    private final QwenVisionClient qwen;
    private final VisionImagePreparer imagePreparer;
    private final ObjectMapper json;

    public DimensionCalibrationReport recognize(String analysisId, int revision) {
        FloorPlanSceneRevision scene = current(analysisId, revision);
        ObjectNode raw = raw(scene);
        requirePixelSource(raw);
        JsonNode preview = raw.path("preview");
        var image = imagePreparer.cachedPreview(preview.path("pngBase64").asText(null),
            preview.path("width").asInt(), preview.path("height").asInt(),
            raw.path("pageNumber").asInt(1), raw.path("pageCount").asInt(1), raw.path("imageSha256").asText(null));
        JsonNode result = qwen.recognizeDimensions(image);
        current(analysisId, revision);
        raw.set("dimensionModelOutput", result.path("dimensionModelOutput"));
        raw.set("dimensionOriginalModelOutput", result.path("dimensionOriginalModelOutput"));
        raw.set("dimensionCalibration", result.path("dimensionCalibration"));
        raw.put("dimensionProviderRequestId", result.path("providerRequestId").asText(null));
        raw.put("dimensionEvidenceImageSha256", image.imageSha256());
        raw.remove("dimensionCalibrationConfirmation");
        DimensionCalibrationReport report = report(raw, analysisId, revision, "mm");
        persist(scene, raw);
        return report;
    }

    public DimensionCalibrationReport get(String analysisId, int revision, String assumedUnit) {
        return report(raw(current(analysisId, revision)), analysisId, revision, assumedUnit);
    }

    public DimensionCalibrationReport confirm(String analysisId, int revision, String reportId, String assumedUnit) {
        FloorPlanSceneRevision scene = current(analysisId, revision);
        ObjectNode raw = raw(scene);
        requirePixelSource(raw);
        DimensionCalibrationReport report = report(raw, analysisId, revision, assumedUnit);
        if (report == null || !Objects.equals(report.getReportId(), reportId)) throw new BusinessException("尺寸识别报告已更新，请重新核对当前参考线");
        if (!"READY".equals(report.getStatus()) || report.getMmPerPixel() == null) throw new BusinessException("尺寸参考不足或比例冲突，不能应用自动比例");
        if (report.getConfirmationId() != null) return report;
        ObjectNode confirmation = raw.putObject("dimensionCalibrationConfirmation");
        confirmation.put("reportId", reportId).put("assumedUnit", assumedUnit).put("imageSha256", report.getImageSha256())
            .put("mmPerPixel", report.getMmPerPixel()).put("confirmationId", "DCAL-" + UUID.randomUUID()).put("confirmedAt", Instant.now().toString());
        current(analysisId, revision);
        persist(scene, raw);
        return report(raw, analysisId, revision, assumedUnit);
    }

    private void persist(FloorPlanSceneRevision scene, ObjectNode raw) {
        if (revisionMapper.compareAndSetDimensionResult(scene.getAnalysisId(), scene.getAnalysisRevision(), scene.getRawResult(), raw.toString()) != 1) {
            throw new BusinessException("识别版本或尺寸报告已变化，请重新读取当前结果");
        }
    }

    private FloorPlanSceneRevision current(String id, int revision) {
        FloorPlanAnalysis analysis = analysisMapper.selectById(id);
        if (analysis == null) throw new BusinessException("分析批次不存在");
        if (FloorPlanAnalysis.STATUS_ANALYZING.equals(analysis.getStatus())) throw new BusinessException("空间识别尚未完成，请稍后校准尺寸");
        if ("cad".equals(analysis.getInputMode())) throw new BusinessException("CAD 请使用文件单位与已有比例校准，图片尺寸自动读取仅支持 PNG/JPG/PDF");
        int active = analysis.getWorkingAnalysisRevision() != null ? analysis.getWorkingAnalysisRevision()
            : analysis.getActiveAnalysisRevision() != null ? analysis.getActiveAnalysisRevision()
            : analysis.getLatestAnalysisRevision() != null ? analysis.getLatestAnalysisRevision() : 1;
        if (revision != active) throw new BusinessException("当前图纸版本已变化，请刷新后重新识别尺寸");
        FloorPlanSceneRevision scene = revisionMapper.selectByRevision(id, revision);
        if (scene == null || FloorPlanSceneRevision.STATUS_FAILED.equals(scene.getRevisionStatus()) || FloorPlanSceneRevision.STATUS_SUPERSEDED.equals(scene.getRevisionStatus())) throw new BusinessException("当前识别版本不可用于比例校准");
        return scene;
    }

    public DimensionCalibrationReport report(JsonNode raw, String analysisId, int revision, String assumedUnit) {
        if (raw == null || !raw.path("dimensionCalibration").isObject()) return null;
        JsonNode original = raw.path("dimensionCalibration");
        JsonNode output = raw.has("dimensionModelOutput") ? raw.path("dimensionModelOutput") : raw.path("modelOutput");
        String hash = raw.path("imageSha256").asText(raw.path("dimensionEvidenceImageSha256").asText(null));
        if (!Objects.equals(hash, original.path("imageSha256").asText(null))) throw new BusinessException("尺寸参考与当前标准底图不一致，请重新读取尺寸");
        var result = DimensionScaleEstimator.estimate(output.path("dimensionReferences"), original.path("imageWidth").asInt(),
            original.path("imageHeight").asInt(), hash, original.path("reportId").asText(), assumedUnit);
        result.setAnalysisId(analysisId);
        result.setAnalysisRevision(revision);
        JsonNode confirmed = raw.path("dimensionCalibrationConfirmation");
        if (Objects.equals(result.getReportId(), confirmed.path("reportId").asText(null)) &&
            Objects.equals(assumedUnit, confirmed.path("assumedUnit").asText(null)) &&
            Objects.equals(hash, confirmed.path("imageSha256").asText(null)) &&
            "READY".equals(result.getStatus()) && Math.abs(result.getMmPerPixel() - confirmed.path("mmPerPixel").asDouble()) < 1e-9) {
            result.setConfirmationId(confirmed.path("confirmationId").asText(null));
            result.setConfirmedAt(confirmed.path("confirmedAt").asText(null));
        }
        return result;
    }

    private ObjectNode raw(FloorPlanSceneRevision scene) {
        try {
            JsonNode result = json.readTree(scene.getRawResult());
            if (result != null && result.isObject()) return (ObjectNode) result;
        } catch (Exception ignored) {}
        throw new BusinessException("该记录缺少原始图纸数据，请重新识别原图或使用手工校准");
    }

    private void requirePixelSource(JsonNode raw) {
        if (!"px".equals(raw.path("units").asText()) || !"SOURCE_PAGE".equals(raw.path("coordinateSystem").asText())) {
            throw new BusinessException("该记录没有标准像素坐标，不能直接应用图片尺寸比例，请重新识别原图或使用手工校准");
        }
        JsonNode preview = raw.path("preview"), bounds = preview.path("bounds");
        if (preview.path("width").asInt() <= 0 || preview.path("height").asInt() <= 0 || !bounds.isObject() ||
            bounds.path("minX").asDouble(Double.NaN) != 0 || bounds.path("minY").asDouble(Double.NaN) != 0 ||
            bounds.path("maxX").asDouble() != preview.path("width").asDouble() || bounds.path("maxY").asDouble() != preview.path("height").asDouble()) {
            throw new BusinessException("底图范围与像素坐标不一致，请重新识别原图或使用手工校准");
        }
    }
}
