package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.VisionProperties;
import com.rsdp.officeproduct.importer.entity.FloorPlanAnalysis;
import com.rsdp.officeproduct.importer.mapper.FloorPlanAnalysisMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FloorPlanJobServiceTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private final FloorPlanService plans = mock(FloorPlanService.class);
    private final FloorPlanAnalysisMapper analyses = mock(FloorPlanAnalysisMapper.class);
    private final QwenVisionClient qwen = mock(QwenVisionClient.class);

    private MockMultipartFile source() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB), "png", bytes);
        Path path = directory.resolve("source.png");
        Files.write(path, bytes.toByteArray());
        when(plans.beginBackground(any(), anyString(), anyString(), anyString(), anyInt()))
            .thenReturn(new FloorPlanService.AnalysisWork("A", path, "source.png", "image/png"));
        return new MockMultipartFile("image", "source.png", "image/png", bytes.toByteArray());
    }

    @Test
    void taskReturnsWhileModelIsBlockedAndPreviewIsPublishedFirst() throws Exception {
        var file = source();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var preview = new AtomicBoolean();
        doAnswer(call -> { preview.set(true); return null; }).when(plans).publishPreview(eq("A"), any(), anyString());
        when(qwen.recognizePrepared(any(), any())).thenAnswer(call -> {
            assertTrue(preview.get(), "底图必须先持久化，再等待 AI");
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return json.createObjectNode();
        });
        doAnswer(call -> {
            if ("DONE".equals(((ObjectNode) call.getArgument(1)).path("stage").asText())) done.countDown();
            return null;
        }).when(plans).updateProgress(eq("A"), any());
        var executors = new VisionTaskExecutors();
        try {
            var service = new FloorPlanJobService(plans, analyses, new VisionImagePreparer(new VisionProperties()), qwen, executors, json);
            assertEquals("A", service.start(file, "P", "source", "http://test", 1).get("analysisId"));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            verify(plans, never()).completeBackground(anyString(), any(), anyString());
            release.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            verify(plans).completeBackground(eq("A"), any(), anyString());
            var order = inOrder(plans);
            order.verify(plans).beginBackground(any(), anyString(), anyString(), anyString(), anyInt());
            order.verify(plans).publishPreview(eq("A"), any(), anyString());
            order.verify(plans).completeBackground(eq("A"), any(), anyString());
        } finally { release.countDown(); executors.destroy(); }
    }

    @Test
    void failureAfterPreviewKeepsPreviewAndReportsFailure() throws Exception {
        var file = source();
        var executors = mock(VisionTaskExecutors.class);
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(executors).executeJob(any());
        when(qwen.recognizePrepared(any(), any())).thenThrow(new BusinessException("模型超时"));
        var service = new FloorPlanJobService(plans, analyses, new VisionImagePreparer(new VisionProperties()), qwen, executors, json);
        service.start(file, "P", "source", "http://test", 1);
        var order = inOrder(plans);
        order.verify(plans).publishPreview(eq("A"), any(), anyString());
        order.verify(plans).failBackground(eq("A"), eq("模型超时"), argThat(value -> "FAILED".equals(value.path("stage").asText()) && value.path("timingsMs").has("prepare")));
        verify(plans, never()).completeBackground(anyString(), any(), anyString());
    }

    @Test
    void rejectedQueueAndRestartedJobsHaveExplicitFailures() throws Exception {
        var file = source();
        var executors = mock(VisionTaskExecutors.class);
        doThrow(new java.util.concurrent.RejectedExecutionException()).when(executors).executeJob(any());
        var service = new FloorPlanJobService(plans, analyses, new VisionImagePreparer(new VisionProperties()), qwen, executors, json);
        service.start(file, "P", "source", "http://test", 1);
        verify(plans).failBackground(eq("A"), contains("繁忙"), any());
        var interrupted = new FloorPlanAnalysis();
        interrupted.setAnalysisId("INTERRUPTED");
        interrupted.setRawResult("{\"processing\":{\"stage\":\"RECOGNIZING\"}}");
        var legacy = new FloorPlanAnalysis();
        legacy.setAnalysisId("LEGACY");
        legacy.setRawResult("{}");
        when(analyses.selectList(any())).thenReturn(List.of(interrupted, legacy));
        service.markInterruptedJobs();
        verify(plans).failBackground(eq("INTERRUPTED"), contains("服务重启"), argThat(value -> "FAILED".equals(value.path("stage").asText())));
        verify(plans, never()).failBackground(eq("LEGACY"), anyString(), any());
    }
}
