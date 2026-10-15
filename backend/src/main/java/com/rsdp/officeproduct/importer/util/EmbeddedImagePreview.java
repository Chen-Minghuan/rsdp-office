package com.rsdp.officeproduct.importer.util;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;

/** Bounded JPEG previews for the upload response. Original image bytes remain in the workbook. */
public final class EmbeddedImagePreview {
    private static final int MAX_SIDE = 240;
    private static final int MAX_PIXELS = 40_000_000;
    private static final int MAX_PREVIEW_BYTES = 48 * 1024;

    private EmbeddedImagePreview() { }

    public static String create(WorkbookImportSource.Image image) {
        try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(image.data()))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) return null;
                int sample = Math.max(1, (int) Math.ceil(Math.max(width, height) / (double) MAX_SIDE));
                var readParam = reader.getDefaultReadParam();
                readParam.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage decoded = reader.read(0, readParam);
                int targetWidth = Math.max(1, (int) Math.round(width * Math.min(1d, MAX_SIDE / (double) Math.max(width, height))));
                int targetHeight = Math.max(1, (int) Math.round(height * Math.min(1d, MAX_SIDE / (double) Math.max(width, height))));
                BufferedImage thumbnail = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = thumbnail.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, targetWidth, targetHeight);
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.drawImage(decoded, 0, 0, targetWidth, targetHeight, null);
                } finally {
                    graphics.dispose();
                    decoded.flush();
                }
                Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
                if (!writers.hasNext()) return null;
                ImageWriter writer = writers.next();
                try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                     ImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
                    writer.setOutput(output);
                    ImageWriteParam param = writer.getDefaultWriteParam();
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    param.setCompressionQuality(0.68f);
                    writer.write(null, new IIOImage(thumbnail, null, null), param);
                    output.flush();
                    if (bytes.size() > MAX_PREVIEW_BYTES) return null;
                    return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
                } finally {
                    writer.dispose();
                    thumbnail.flush();
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
