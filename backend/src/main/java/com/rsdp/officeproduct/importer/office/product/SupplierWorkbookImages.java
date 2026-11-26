package com.rsdp.officeproduct.importer.office.product;

import com.rsdp.officeproduct.importer.common.BusinessException;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.rsdp.officeproduct.importer.office.product.LocalProductModels.*;

final class SupplierWorkbookImages {
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final Pattern DISPIMG = Pattern.compile("(?i)\\bDISPIMG\\s*\\(\\s*\"(ID_[A-Za-z0-9]+)\"");
    private static final int MAX_METADATA_BYTES = 5 * 1024 * 1024;
    private static final int MAX_PREVIEW_IMAGE_BYTES = 32 * 1024 * 1024;

    record Picture(PackagePart data, String description) {}
    record Anchor(int row, int column, long offset, Picture picture) {}

    private final XSSFWorkbook workbook;
    private final WorkbookPreviewCache.Session cached;
    private final Map<String, Picture> cellImages;
    private final Map<PackagePart, String> keys = new HashMap<>();
    private final Map<String, ImageInput> images = new LinkedHashMap<>();
    private long totalBytes;

    SupplierWorkbookImages(XSSFWorkbook workbook, WorkbookPreviewCache.Session cached) throws IOException, InvalidFormatException {
        this.workbook = workbook;
        this.cached = cached;
        PackagePart cellPart = workbook.getPackage().getPart(PackagingURIHelper.createPartName("/xl/cellimages.xml"));
        cellImages = new HashMap<>();
        if (cellPart == null) return;
        var nodes = xml(cellPart).getElementsByTagNameNS("*", "pic");
        for (int i = 0; i < nodes.getLength(); i++) {
            var node = (Element) nodes.item(i);
            Picture picture = readPicture(cellPart, node);
            if (picture != null) cellImages.put(first(node, "cNvPr").getAttribute("name"), picture);
        }
    }

    List<Anchor> anchors(XSSFSheet sheet) throws IOException, InvalidFormatException {
        var drawing = sheet.getDrawingPatriarch();
        if (drawing == null) return List.of();
        var part = drawing.getPackagePart();
        var result = new ArrayList<Anchor>();
        // Descendant pictures also cover grouped drawings, which share the outer anchor.
        for (var child = xml(part).getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element anchor)) continue;
            Element from = first(anchor, "from");
            if (from == null) continue;
            int row = (int) coordinate(from, "row", -1);
            int column = (int) coordinate(from, "col", -1);
            if (row < 0 || column < 0) continue;
            long offset = coordinate(from, "rowOff", 0);
            var pictures = anchor.getElementsByTagNameNS("*", "pic");
            for (int i = 0; i < pictures.getLength(); i++) {
                Picture picture = readPicture(part, (Element) pictures.item(i));
                if (picture != null) result.add(new Anchor(row, column, offset, picture));
            }
        }
        result.sort(Comparator.comparingInt(Anchor::row).thenComparingLong(Anchor::offset));
        return result;
    }

    Picture cellPicture(XSSFSheet sheet, int row, int column) {
        var sourceRow = sheet.getRow(row);
        var cell = sourceRow == null || column < 0 ? null : sourceRow.getCell(column);
        if (cell == null || cell.getCellType() != CellType.FORMULA) return null;
        var match = DISPIMG.matcher(cell.getCellFormula());
        if (!match.find()) return null;
        Picture picture = cellImages.get(match.group(1));
        if (picture == null) throw new BusinessException("无法读取该单元格的 WPS 图片");
        return picture;
    }

    String include(Picture picture) throws IOException {
        PackagePart part = picture.data();
        String previous = keys.get(part);
        if (previous != null) return previous;
        byte[] content;
        try (InputStream input = part.getInputStream()) {
            content = input.readNBytes(MAX_IMAGE_BYTES + 1);
        }
        ImageInput image = new ImageInput(null, part.getContentType(), content);
        LocalProductService.validateImage(image);
        String key = WorkbookPreviewCache.key(content);
        if (!images.containsKey(key)) {
            if (cached == null && totalBytes + content.length > MAX_PREVIEW_IMAGE_BYTES) {
                throw new BusinessException("预览图片超过 32 MB，剩余图片请拆分工作簿后导入");
            }
            totalBytes += content.length;
            images.put(key, cached == null ? image : cached.store(key, image));
        }
        keys.put(part, key);
        return key;
    }

    Map<String, ImageInput> includedImages() {
        return Map.copyOf(images);
    }

    private Picture readPicture(PackagePart part, Element node) throws InvalidFormatException {
        Element properties = first(node, "cNvPr");
        Element blip = first(node, "blip");
        if (properties == null || blip == null) return null;
        var relation = part.getRelationship(blip.getAttributeNS(REL_NS, "embed"));
        if (relation == null || relation.getTargetMode() != TargetMode.INTERNAL) return null;
        var uri = PackagingURIHelper.resolvePartUri(part.getPartName().getURI(), relation.getTargetURI());
        if (!uri.getPath().startsWith("/xl/media/")) return null;
        PackagePart data = workbook.getPackage().getPart(PackagingURIHelper.createPartName(uri));
        return data == null ? null : new Picture(data, properties.getAttribute("descr"));
    }

    private static Element first(Element element, String name) {
        return (Element) element.getElementsByTagNameNS("*", name).item(0);
    }

    private static long coordinate(Element element, String name, long fallback) {
        var value = first(element, name);
        if (value == null) return fallback;
        try {
            return Long.parseLong(value.getTextContent());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static Element xml(PackagePart part) throws IOException {
        try (InputStream input = part.getInputStream()) {
            byte[] metadata = input.readNBytes(MAX_METADATA_BYTES + 1);
            if (metadata.length > MAX_METADATA_BYTES) throw new IOException("工作簿图片索引过大");
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(metadata)).getDocumentElement();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("无法读取工作簿的内嵌图片", e);
        }
    }
}
