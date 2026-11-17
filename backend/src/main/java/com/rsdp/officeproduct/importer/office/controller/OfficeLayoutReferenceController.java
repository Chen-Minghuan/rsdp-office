package com.rsdp.officeproduct.importer.office.controller;

import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.common.Result;
import com.rsdp.officeproduct.importer.dto.response.FloorPlanAnalysisResponse;
import com.rsdp.officeproduct.importer.office.service.OfficeProjectAccessService;
import com.rsdp.officeproduct.importer.service.FloorPlanService;
import com.rsdp.officeproduct.importer.service.QwenVisionClient;
import java.util.List;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/office/floors/{floorId}/layout-reference")
@RequiredArgsConstructor
public class OfficeLayoutReferenceController {
    private final OfficeProjectAccessService access;
    private final FloorPlanService floorPlans;
    private final QwenVisionClient vision;

    @PostMapping("/recognize")
    public Result<List<QwenVisionClient.FurnitureMark>> recognize(@PathVariable String floorId,
        @RequestParam String analysisId, @RequestParam int revision, @RequestParam MultipartFile image) {
        requirePlan(floorId, analysisId, revision);
        return Result.ok(vision.recognizeFurniture(image));
    }

    @GetMapping("/source")
    public ResponseEntity<Resource> source(@PathVariable String floorId, @RequestParam String analysisId, @RequestParam int revision) {
        var plan = requirePlan(floorId, analysisId, revision);
        String url = plan.getPreviewUrl() != null ? plan.getPreviewUrl() : plan.getImageUrl();
        if (url == null) throw new BusinessException("当前图纸没有可裁切的底图");
        try {
            String assetPath = URI.create(url).getPath();
            if (assetPath == null || !assetPath.matches(".*/uploads/floor-plan/[A-Za-z0-9_-]+\\.(png|jpe?g|webp)")) {
                throw new BusinessException("当前图纸没有可裁切的底图");
            }
            String filename = assetPath.substring(assetPath.lastIndexOf('/') + 1);
            Path root = Path.of(System.getProperty("user.dir"), "uploads", "floor-plan").toRealPath();
            Path image = root.resolve(filename).toRealPath();
            if (!image.startsWith(root) || !Files.isRegularFile(image)) throw new BusinessException("当前图纸底图不可用");
            String mime = switch (filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT)) {
                case "png" -> "image/png";
                case "webp" -> "image/webp";
                default -> "image/jpeg";
            };
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime)).cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(image));
        } catch (IOException | IllegalArgumentException failure) {
            throw new BusinessException("当前图纸底图不可用");
        }
    }

    private FloorPlanAnalysisResponse requirePlan(String floorId, String analysisId, int revision) {
        access.requireFloorAccess(floorId);
        if (revision < 1) throw new BusinessException("图纸版本无效");
        var plan = floorPlans.getAnalysis(analysisId, null);
        if (!floorId.equals(plan.getProjectId()) || !Integer.valueOf(revision).equals(plan.getAnalysisRevision())) {
            throw new BusinessException("当前图纸版本不属于此楼层");
        }
        return plan;
    }
}
