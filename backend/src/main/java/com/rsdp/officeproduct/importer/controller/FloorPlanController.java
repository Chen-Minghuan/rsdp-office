package com.rsdp.officeproduct.importer.controller;

import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.dto.request.FloorPlanConfirmRequest;
import com.rsdp.officeproduct.importer.dto.response.FloorPlanAnalysisResponse;
import com.rsdp.officeproduct.importer.service.FloorPlanService;
import com.rsdp.officeproduct.importer.service.FloorPlanDimensionService;
import com.rsdp.officeproduct.importer.service.FloorPlanJobService;
import com.rsdp.officeproduct.importer.config.VisionProperties;
import com.rsdp.officeproduct.importer.dto.response.DimensionCalibrationReport;
import com.rsdp.officeproduct.importer.dto.request.DimensionCalibrationConfirmRequest;
import jakarta.validation.constraints.Min;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.Map;

/**
 * 户型图 / 无限画布分析接口。
 */
@RestController
@RequestMapping("/api/v1/floor-plan")
@RequiredArgsConstructor
@Validated
public class FloorPlanController {

    private final FloorPlanService floorPlanService;
    private final FloorPlanDimensionService floorPlanDimensionService;
    private final FloorPlanJobService floorPlanJobService;
    private final VisionProperties visionProperties;

    /**
     * 上传户型图并创建分析任务。
     *
     * @param image     户型图片（jpg/png/pdf），可选
     * @param cad       CAD 图纸（dwg/dxf），可选
     * @param hint      用户补充说明，可选
     * @param projectId 归属项目 ID，可选
     * @param sourceName 户型名称/备注，可选
     * @return analysisId + taskId
     */
    @PostMapping("/analyze")
    public Result<Map<String, String>> analyze(
        @RequestParam(value = "image", required = false) MultipartFile image,
        @RequestParam(value = "cad", required = false) MultipartFile cad,
        @RequestParam(required = false) String hint,
        @RequestParam(required = false) String projectId,
        @RequestParam(required = false) String sourceName,
        @RequestParam(defaultValue = "1") int pageNumber,
        @RequestParam(defaultValue = "false") boolean background,
        HttpServletRequest request) {
        String baseUrl = ServletUriComponentsBuilder.fromRequestUri(request)
            .replacePath("").toUriString();
        if (background && (cad == null || cad.isEmpty()) && !org.springframework.util.StringUtils.hasText(visionProperties.getUrl())) {
            return Result.ok(floorPlanJobService.start(image, projectId, sourceName, baseUrl, pageNumber));
        }
        return Result.ok(floorPlanService.analyze(image, cad, hint, projectId, sourceName, baseUrl, pageNumber));
    }

    /**
     * 查询分析详情与空间列表（前端轮询入口）。
     */
    @GetMapping("/{analysisId}")
    public Result<FloorPlanAnalysisResponse> getAnalysis(
        @PathVariable String analysisId,
        @RequestParam(required = false) Integer analysisRevision) {
        return Result.ok(floorPlanService.getAnalysis(analysisId, analysisRevision));
    }

    /**
     * 人工校正/确认空间列表。
     */
    @PutMapping("/{analysisId}/rooms")
    public Result<FloorPlanAnalysisResponse> confirmRooms(
        @PathVariable String analysisId,
        @Valid @RequestBody FloorPlanConfirmRequest request) {
        return Result.ok(floorPlanService.confirmRooms(analysisId, request));
    }

    @PostMapping("/{analysisId}/dimension-calibration/recognize")
    public Result<DimensionCalibrationReport> recognizeDimensions(@PathVariable String analysisId,
        @RequestParam @Min(1) int analysisRevision) {
        return Result.ok(floorPlanDimensionService.recognize(analysisId, analysisRevision));
    }

    @GetMapping("/{analysisId}/dimension-calibration")
    public Result<DimensionCalibrationReport> dimensionCalibration(@PathVariable String analysisId,
        @RequestParam @Min(1) int analysisRevision, @RequestParam(defaultValue = "mm") String assumedUnit) {
        return Result.ok(floorPlanDimensionService.get(analysisId, analysisRevision, assumedUnit));
    }

    @PostMapping("/{analysisId}/dimension-calibration/confirm")
    public Result<DimensionCalibrationReport> confirmDimensionCalibration(@PathVariable String analysisId,
        @Valid @RequestBody DimensionCalibrationConfirmRequest request) {
        return Result.ok(floorPlanDimensionService.confirm(analysisId, request.getAnalysisRevision(), request.getReportId(), request.getAssumedUnit()));
    }
}
