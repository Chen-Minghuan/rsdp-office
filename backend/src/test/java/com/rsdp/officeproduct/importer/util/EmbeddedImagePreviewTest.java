package com.rsdp.officeproduct.importer.util;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddedImagePreviewTest {
    @Test
    void createsSmallPreviewWithoutChangingOriginalImage() throws Exception {
        BufferedImage original = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(original, "png", png);
        byte[] source = png.toByteArray();
        String preview = EmbeddedImagePreview.create(new WorkbookImportSource.Image(source, "png", ""));
        assertNotNull(preview);
        assertTrue(preview.startsWith("data:image/jpeg;base64,"));
        byte[] thumbnail = Base64.getDecoder().decode(preview.substring(preview.indexOf(',') + 1));
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(thumbnail));
        assertEquals(240, decoded.getWidth());
        assertEquals(160, decoded.getHeight());
        assertTrue(thumbnail.length <= 48 * 1024);
        assertArrayEquals(source, png.toByteArray());
    }

    @Test
    void unsupportedImageHasNoPreview() {
        assertNull(EmbeddedImagePreview.create(new WorkbookImportSource.Image(new byte[]{1, 2, 3}, "png", "")));
    }
}
