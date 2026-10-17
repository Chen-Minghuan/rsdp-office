package com.rsdp.officeproduct.importer.service;

import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.VisionProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class VisionImagePreparerTest {
    private final VisionProperties properties = new VisionProperties();
    private final VisionImagePreparer preparer = new VisionImagePreparer(properties);

    @Test
    void cacheUsesContentsPageAndProcessingParametersAndProtectsImageBytes() throws Exception {
        var file = pdf();
        var first = preparer.prepareCached(file, 1);
        assertFalse(first.cacheHit());
        var renamed = new MockMultipartFile("file", "renamed.pdf", "application/pdf", file.getBytes());
        assertTrue(preparer.prepareCached(renamed, 1).cacheHit());
        assertFalse(preparer.prepareCached(file, 2).cacheHit());
        properties.setPdfDpi(72);
        var lowerDpi = preparer.prepareCached(file, 1);
        assertFalse(lowerDpi.cacheHit());
        assertEquals(200, lowerDpi.image().width());
        properties.setMaxImageEdge(256);
        assertFalse(preparer.prepareCached(file, 1).cacheHit());
        byte original = lowerDpi.image().png()[0];
        lowerDpi.image().png()[0] = 0;
        assertEquals(original, lowerDpi.image().png()[0]);
        // Failures cannot poison the cache or leave a pending single-flight future.
        var invalid = new MockMultipartFile("file", new byte[]{1, 2, 3});
        assertThrows(BusinessException.class, () -> preparer.prepareCached(invalid, 1));
        assertThrows(BusinessException.class, () -> preparer.prepareCached(invalid, 1));
    }

    @Test
    void concurrentIdenticalPreparationIsShared() throws Exception {
        var file = pdf();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(6)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var requests = new java.util.ArrayList<java.util.concurrent.Future<VisionImagePreparer.Preparation>>();
            for (int i = 0; i < 6; i++) requests.add(pool.submit(() -> { start.await(); return preparer.prepareCached(file, 1); }));
            start.countDown();
            int conversions = 0;
            VisionImagePreparer.PreparedImage shared = null;
            for (var request : requests) {
                var result = request.get(10, java.util.concurrent.TimeUnit.SECONDS);
                if (!result.cacheHit()) conversions++;
                if (shared == null) shared = result.image();
                else assertSame(shared, result.image());
            }
            assertEquals(1, conversions);
        }
    }

    @Test
    void selectedPdfPageIsRenderedWithItsOwnSizeAndContent() throws Exception {
        MockMultipartFile pdf = pdf();
        var first = preparer.prepare(pdf, 1);
        var second = preparer.prepare(pdf, 2);
        assertEquals(2, second.pageCount());
        assertEquals(2, second.pageNumber());
        assertEquals(400, first.width());
        assertEquals(600, second.width());
        assertEquals(first.sourceSha256(), second.sourceSha256());
        assertNotEquals(first.imageSha256(), second.imageSha256());
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(second.png()));
        assertEquals(Color.BLUE.getRGB(), image.getRGB(20, 20));
    }

    @Test
    void rejectsInvalidPagesUnreadableFilesAndEmptyInput() throws Exception {
        MockMultipartFile pdf = pdf();
        assertThrows(BusinessException.class, () -> preparer.prepare(pdf, 0));
        assertThrows(BusinessException.class, () -> preparer.prepare(pdf, 3));
        assertThrows(BusinessException.class, () -> preparer.prepare(new MockMultipartFile("file", new byte[0]), 1));
        assertThrows(BusinessException.class, () -> preparer.prepare(new MockMultipartFile("file", "plan.png", "image/png", "not-image".getBytes()), 1));
    }

    @Test
    void imageDimensionsAreBoundedAndTransparencyUsesWhiteBackground() throws Exception {
        properties.setMaxImageEdge(256);
        var transparent = new BufferedImage(1024, 512, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(transparent, "png", bytes);
        var file = new MockMultipartFile("file", "plan.png", "image/png", bytes.toByteArray());
        var result = preparer.prepare(file, 1);
        assertEquals(256, result.width());
        assertEquals(128, result.height());
        assertEquals(Color.WHITE.getRGB(), ImageIO.read(new ByteArrayInputStream(result.png())).getRGB(5, 5));
        assertThrows(BusinessException.class, () -> preparer.prepare(file, 2));
    }

    @Test
    void cachedPreviewPreservesExactImageAndChecksDimensionsHashAndEncoding() throws Exception {
        var prepared = preparer.prepare(pdf(), 2);
        var cached = preparer.cachedPreview(prepared.base64(), prepared.width(), prepared.height(), 2, 2, prepared.imageSha256());
        assertArrayEquals(prepared.png(), cached.png());
        assertEquals(2, cached.pageNumber());
        assertEquals(prepared.imageSha256(), cached.imageSha256());
        assertThrows(BusinessException.class, () -> preparer.cachedPreview(prepared.base64(), 100, prepared.height(), 2, 2, prepared.imageSha256()));
        assertThrows(BusinessException.class, () -> preparer.cachedPreview(prepared.base64(), prepared.width(), prepared.height(), 2, 2, "wrong-hash"));
        assertThrows(BusinessException.class, () -> preparer.cachedPreview("!bad", 100, 100, 1, 1, null));
        var crops = preparer.dimensionCrops(cached);
        assertEquals(4, crops.size());
        var bottom = crops.stream().filter(crop -> crop.name().equals("BOTTOM")).findFirst().orElseThrow();
        assertEquals(cached.height(), bottom.y() + bottom.height());
        assertEquals(cached.width(), bottom.width());
        assertArrayEquals(prepared.png(), cached.png());
    }

    private MockMultipartFile pdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage first = new PDPage(new PDRectangle(200, 100));
            PDPage second = new PDPage(new PDRectangle(300, 150));
            document.addPage(first);
            document.addPage(second);
            try (PDPageContentStream content = new PDPageContentStream(document, second)) {
                content.setNonStrokingColor(Color.BLUE);
                content.addRect(0, 0, 300, 150);
                content.fill();
            }
            document.save(out);
            return new MockMultipartFile("file", "plans.pdf", "application/pdf", out.toByteArray());
        }
    }
}
