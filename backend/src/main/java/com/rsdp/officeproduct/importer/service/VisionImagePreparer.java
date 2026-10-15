package com.rsdp.officeproduct.importer.service;

import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.VisionProperties;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;

/** 模型输入与预览共用同一张标准图；原始文件不被覆盖。 */
@Service
@RequiredArgsConstructor
public class VisionImagePreparer {
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    private static final int MAX_DATA_URI_BYTES = 20_000_000;
    private final VisionProperties properties;

    public record PreparedImage(byte[] png, int width, int height, int pageNumber, int pageCount,
                                String sourceSha256, String imageSha256) {
        public String base64() { return Base64.getEncoder().encodeToString(png); }
        public String dataUri() { return "data:image/png;base64," + base64(); }
    }

    public record DimensionCrop(String name, int x, int y, int width, int height, String dataUri) {}

    public List<DimensionLineLocator.Line> dimensionLines(PreparedImage image) {
        try {
            return DimensionLineLocator.locate(ImageIO.read(new ByteArrayInputStream(image.png())));
        } catch (IOException | RuntimeException failure) {
            throw new BusinessException("无法定位尺寸线，请重试或使用手工校准");
        }
    }

    /** Enlarged annotation margins improve endpoint grounding without changing the source page. */
    public List<DimensionCrop> dimensionCrops(PreparedImage image) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(image.png()));
            int bandX = Math.max(1, (int) Math.ceil(image.width() * 0.18));
            int bandY = Math.max(1, (int) Math.ceil(image.height() * 0.18));
            int[][] boxes = {{0, 0, image.width(), bandY}, {0, image.height() - bandY, image.width(), bandY},
                {0, 0, bandX, image.height()}, {image.width() - bandX, 0, bandX, image.height()}};
            String[] names = {"TOP", "BOTTOM", "LEFT", "RIGHT"};
            List<DimensionCrop> crops = new ArrayList<>();
            for (int index = 0; index < boxes.length; index++) {
                int[] box = boxes[index];
                double factor = Math.min(2.0, 3072.0 / Math.max(box[2], box[3]));
                BufferedImage enlarged = new BufferedImage(Math.max(1, (int) (box[2] * factor)), Math.max(1, (int) (box[3] * factor)), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = enlarged.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(source.getSubimage(box[0], box[1], box[2], box[3]), 0, 0, enlarged.getWidth(), enlarged.getHeight(), null);
                } finally { graphics.dispose(); }
                ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(enlarged, "png", out);
                crops.add(new DimensionCrop(names[index], box[0], box[1], box[2], box[3], "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray())));
            }
            return crops;
        } catch (IOException | RuntimeException failure) {
            throw new BusinessException("无法准备尺寸标注放大图，请重试或使用手工校准");
        }
    }

    public PreparedImage prepare(MultipartFile file, int pageNumber) {
        if (file == null || file.isEmpty()) throw new BusinessException("请先上传图片或 PDF");
        if (file.getSize() > MAX_FILE_BYTES) throw new BusinessException("图纸文件不能超过 50MB");
        if (pageNumber < 1) throw new BusinessException("PDF 页码必须从 1 开始");
        int maxEdge = properties.getMaxImageEdge();
        if (maxEdge < 256 || maxEdge > 4096 || properties.getPdfDpi() < 36 || properties.getPdfDpi() > 300) {
            throw new BusinessException("识别图像尺寸或 PDF DPI 配置无效");
        }
        try {
            byte[] bytes = file.getBytes();
            boolean pdf = bytes.length >= 5 && new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
            BufferedImage image;
            int pageCount = 1;
            if (pdf) {
                try (PDDocument document = Loader.loadPDF(bytes)) {
                    pageCount = document.getNumberOfPages();
                    if (pageNumber > pageCount) throw new BusinessException("PDF 共 " + pageCount + " 页，请选择有效页码");
                    var crop = document.getPage(pageNumber - 1).getCropBox();
                    double userUnit = document.getPage(pageNumber - 1).getUserUnit();
                    double edge = Math.max(crop.getWidth(), crop.getHeight()) * userUnit;
                    if (!Double.isFinite(edge) || edge <= 0) throw new BusinessException("PDF 页面尺寸无效");
                    float scale = (float) Math.min(properties.getPdfDpi() / 72.0, maxEdge / edge);
                    image = new PDFRenderer(document).renderImage(pageNumber - 1, scale, ImageType.RGB);
                }
            } else {
                if (pageNumber != 1) throw new BusinessException("图片仅支持第 1 页");
                image = readImage(bytes, maxEdge);
            }
            BufferedImage prepared = resize(image, maxEdge);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(prepared, "png", out);
            byte[] png = out.toByteArray();
            if ((png.length + 2L) / 3L * 4L + 22 > MAX_DATA_URI_BYTES) {
                throw new BusinessException("图像编码后过大，请缩小图像或降低识别尺寸");
            }
            return new PreparedImage(png, prepared.getWidth(), prepared.getHeight(), pageNumber, pageCount,
                sha256(bytes), sha256(png));
        } catch (InvalidPasswordException e) {
            throw new BusinessException("PDF 已加密，请上传可读取的 PDF");
        } catch (IOException e) {
            throw new BusinessException("无法读取图纸，请检查图片或 PDF 是否完整");
        }
    }

    private BufferedImage readImage(byte[] bytes, int maxEdge) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BusinessException("无法读取图片，请上传 PNG、JPG 或 PDF");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg") && !format.equalsIgnoreCase("jpg")) {
                    throw new BusinessException("当前直接识别支持 PNG、JPG 或 PDF");
                }
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0) throw new BusinessException("图片尺寸无效");
                var params = reader.getDefaultReadParam();
                int sample = Math.max(1, (int) Math.ceil(Math.max(width, height) / (double) maxEdge));
                params.setSourceSubsampling(sample, sample, 0, 0);
                return reader.read(0, params);
            } finally { reader.dispose(); }
        }
    }

    private BufferedImage resize(BufferedImage source, int maxEdge) {
        double scale = Math.min(1.0, maxEdge / (double) Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        return result;
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Reuse exactly the cached model input; never rerender another PDF page or change its scale. */
    public PreparedImage cachedPreview(String base64, int width, int height, int pageNumber, int pageCount, String expectedHash) {
        if (base64 == null || base64.length() > MAX_DATA_URI_BYTES || width <= 0 || height <= 0 || width > 4096 || height > 4096) {
            throw new BusinessException("该记录缺少有效标准底图，请重新识别原图或使用手工校准");
        }
        try {
            byte[] png = Base64.getDecoder().decode(base64);
            try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new BusinessException("保存的标准底图不可读取");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    if (reader.getWidth(0) != width || reader.getHeight(0) != height) throw new BusinessException("标准底图尺寸与识别坐标不一致，请重新识别原图");
                } finally { reader.dispose(); }
            }
            String hash = sha256(png);
            if (expectedHash != null && !expectedHash.isBlank() && !hash.equals(expectedHash)) throw new BusinessException("标准底图已变化，请重新识别原图");
            return new PreparedImage(png, width, height, pageNumber, pageCount, hash, hash);
        } catch (IllegalArgumentException | IOException e) {
            throw new BusinessException("保存的标准底图无效，请重新识别原图或使用手工校准");
        }
    }
}
