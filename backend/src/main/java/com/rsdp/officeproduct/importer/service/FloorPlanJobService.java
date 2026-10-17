package com.rsdp.officeproduct.importer.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.FloorPlanAnalysis;
import com.rsdp.officeproduct.importer.mapper.FloorPlanAnalysisMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class FloorPlanJobService {
    private final FloorPlanService plans;
    private final FloorPlanAnalysisMapper analyses;
    private final VisionImagePreparer images;
    private final QwenVisionClient qwen;
    private final VisionTaskExecutors executors;
    private final ObjectMapper json;

    public Map<String, String> start(MultipartFile file, String projectId, String sourceName, String baseUrl, int page) {
        // The source and task are committed before submission; never retain the servlet's temporary upload.
        var work = plans.beginBackground(file, projectId, sourceName, baseUrl, page);
        var progress = new RecognitionProgress(json, value -> plans.updateProgress(work.analysisId(), value));
        progress.stage("QUEUED");
        try { executors.executeJob(() -> run(work, baseUrl, page, progress)); }
        catch (RuntimeException full) {
            progress.stage("FAILED");
            plans.failBackground(work.analysisId(), "识别任务繁忙，请稍后重试", progress.snapshot());
        }
        return Map.of("analysisId", work.analysisId(), "taskId", "TASK-" + work.analysisId());
    }

    private void run(FloorPlanService.AnalysisWork work, String baseUrl, int page, RecognitionProgress progress) {
        try {
            progress.stage("PREPARING");
            long started = System.nanoTime();
            var preparation = images.prepareCached(new StoredFile(work.sourceFile(), work.filename(), work.contentType()), page);
            progress.cacheHit(preparation.cacheHit());
            var image = preparation.image();
            ObjectNode raw = json.createObjectNode().put("imageSha256", image.imageSha256()).put("sourceSha256", image.sourceSha256());
            var bounds = raw.putObject("drawingBounds").put("minX", 0).put("minY", 0).put("maxX", image.width()).put("maxY", image.height());
            raw.putObject("preview").put("width", image.width()).put("height", image.height()).put("pngBase64", image.base64()).set("bounds", bounds.deepCopy());
            raw.putObject("unitResolution").put("canonicalUnit", "px").put("confirmed", false).put("pageNumber", image.pageNumber()).put("pageCount", image.pageCount());
            raw.set("processing", progress.snapshot());
            plans.publishPreview(work.analysisId(), raw, baseUrl);
            progress.completed("prepare", started);
            // The preview is durable and pollable before either model request finishes.
            ObjectNode result = (ObjectNode) qwen.recognizePrepared(image, progress);
            progress.stage("SAVING");
            started = System.nanoTime();
            result.set("processing", progress.snapshot());
            plans.completeBackground(work.analysisId(), result, baseUrl);
            progress.completed("save", started);
            progress.stage("DONE");
            log.info("图纸识别完成 analysisId={} processing={}", work.analysisId(), progress.snapshot());
        } catch (Exception failure) {
            progress.stage("FAILED");
            String message = failure instanceof BusinessException ? failure.getMessage() : "后台识别未完成，请重试";
            plans.failBackground(work.analysisId(), message, progress.snapshot());
            log.warn("图纸识别失败 analysisId={} type={} processing={}", work.analysisId(), failure.getClass().getSimpleName(), progress.snapshot());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void markInterruptedJobs() {
        for (var analysis : analyses.selectList(new QueryWrapper<FloorPlanAnalysis>().eq("status", FloorPlanAnalysis.STATUS_ANALYZING))) {
            try {
                var raw = json.readTree(analysis.getRawResult() == null ? "{}" : analysis.getRawResult());
                if (!raw.path("processing").isObject()) continue; // Leave legacy/other task types alone.
                ObjectNode progress = (ObjectNode) raw.path("processing").deepCopy();
                progress.put("stage", "FAILED");
                plans.failBackground(analysis.getAnalysisId(), "服务重启中断了识别，请重新识别；已生成底图保留", progress);
            } catch (java.io.IOException invalid) { log.warn("无法读取任务进度 analysisId={}", analysis.getAnalysisId()); }
        }
    }

    private record StoredFile(Path path, String filename, String contentType) implements MultipartFile {
        public String getName() { return "image"; }
        public String getOriginalFilename() { return filename; }
        public String getContentType() { return contentType; }
        public boolean isEmpty() { return getSize() == 0; }
        public long getSize() { try { return Files.size(path); } catch (IOException failure) { throw new BusinessException("原图文件不可读取"); } }
        public byte[] getBytes() throws IOException { return Files.readAllBytes(path); }
        public InputStream getInputStream() throws IOException { return Files.newInputStream(path); }
        public void transferTo(File target) throws IOException { Files.copy(path, target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }
}
