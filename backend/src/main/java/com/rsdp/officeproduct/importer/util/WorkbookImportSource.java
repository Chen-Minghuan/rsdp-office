package com.rsdp.officeproduct.importer.util;

import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.response.ProductImportPreviewRow;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.w3c.dom.Node;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Reads supplier rows and both ordinary drawing and WPS DISPIMG image references. */
public final class WorkbookImportSource {
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9]{5,30}");
    private static final Pattern IMAGE_ID = Pattern.compile("ID_[A-Za-z0-9]+");
    private static final Pattern DESCRIPTION_CODE = Pattern.compile("^(?=[A-Za-z0-9]*\\d)[A-Za-z0-9]{5,30}");
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private WorkbookImportSource() { }

    public record Image(byte[] data, String extension, String description) { }
    public record Source(List<ProductImportPreviewRow> rows, Map<Integer, List<Image>> images) { }
    private record LocatedImage(Image image, int row, long rowOffset, int column, long columnOffset, int ordinal) { }

    public static Source read(MultipartFile file) {
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".xlsx")) return new Source(ImportRowCodec.read(file), Map.of());
        try (InputStream in = file.getInputStream(); XSSFWorkbook workbook = new XSSFWorkbook(in)) {
            boolean supplier = isSupplierWorkbook(workbook);
            List<ProductImportPreviewRow> rows = supplier ? readSupplierRows(workbook) : ImportRowCodec.read(file);
            Map<Integer, List<Image>> images = readImages(file, workbook, rows, supplier);
            return new Source(rows, images);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("无法读取 Excel 内嵌图片或商品行", e);
        }
    }

    private static boolean isSupplierWorkbook(XSSFWorkbook workbook) {
        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            XSSFSheet sheet = workbook.getSheetAt(s);
            for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 12); r++) {
                Row row = sheet.getRow(r);
                if (row != null && "ITEM NO.".equalsIgnoreCase(text(row.getCell(1)))
                    && "ITEM NAME".equalsIgnoreCase(text(row.getCell(2)))) return true;
            }
        }
        return false;
    }

    private static List<ProductImportPreviewRow> readSupplierRows(XSSFWorkbook workbook) {
        List<ProductImportPreviewRow> result = new ArrayList<>();
        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            XSSFSheet sheet = workbook.getSheetAt(s);
            Map<Integer, String> mergedRemarks = new HashMap<>();
            for (CellRangeAddress region : sheet.getMergedRegions()) {
                if (region.getFirstColumn() != 5 || region.getLastColumn() != 5
                    || region.getFirstRow() >= region.getLastRow()) continue;
                Row firstRow = sheet.getRow(region.getFirstRow());
                String remark = firstRow == null ? "" : text(firstRow.getCell(5));
                for (int coveredRow = region.getFirstRow(); coveredRow <= region.getLastRow(); coveredRow++) {
                    mergedRemarks.put(coveredRow, remark);
                }
            }
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row excelRow = sheet.getRow(r);
                if (excelRow == null) continue;
                String code = text(excelRow.getCell(1));
                String name = text(excelRow.getCell(2));
                if (!CODE.matcher(code).matches()) continue;
                String originalSize = text(excelRow.getCell(4));
                List<String> alternatives = SupplierProductAdapter.widthAlternatives(originalSize);
                List<String> sizes = alternatives.isEmpty() ? List.of(originalSize) : alternatives;
                if (result.size() + sizes.size() > 500) throw new BusinessException("单次导入不能超过 500 行");
                for (String size : sizes) {
                    ProductImportPreviewRow row = new ProductImportPreviewRow();
                    row.setRowIndex(result.size() + 2);
                    row.setSourceSheetIndex(s);
                    row.setSourceRowIndex(r + 1);
                    row.setSourceSheetName(sheet.getSheetName());
                    Map<String, String> fields = row.getFields();
                    fields.put("externalCode", code);
                    fields.put("productName", name);
                    fields.put("colorPrimaryName", text(excelRow.getCell(3)));
                    fields.put("colorText", text(excelRow.getCell(3)));
                    fields.put("sizeText", size);
                    SupplierProductAdapter.fillDimensions(size, fields);
                    fields.put("description", mergedRemarks.getOrDefault(r, text(excelRow.getCell(5))));
                    fields.put("packageCount", integerText(excelRow.getCell(6)));
                    fields.put("packageVolumeCbm", decimalText(excelRow.getCell(7), 9));
                    fields.put("grossWeight", numberText(excelRow.getCell(8), 3));
                    fields.put("retailPrice", numberText(excelRow.getCell(9), 2));
                    fields.put("factoryPrice", numberText(excelRow.getCell(11), 2));
                    if (!alternatives.isEmpty()) {
                        row.setSourceSizeText(originalSize);
                        row.setSourceVariantCount(alternatives.size());
                        row.setSourceVariantNote("原行的价格、包数、体积和重量按已确认的共用规则复制到两个规格；请核对其他同类来源行是否也适用。");
                    }
                    result.add(row);
                }
            }
        }
        if (result.isEmpty()) throw new BusinessException("供应商工作簿没有可导入的商品行");
        return result;
    }

    private static String text(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.FORMULA) {
            try { return cell.getCellFormula().trim(); } catch (Exception ignored) { return ""; }
        }
        return new DataFormatter().formatCellValue(cell).trim();
    }

    private static String numberText(Cell cell) {
        return numberText(cell, 3);
    }

    private static String integerText(Cell cell) {
        if (cell == null) return "";
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            if (!Double.isFinite(value)) return "";
            java.math.BigDecimal exact = java.math.BigDecimal.valueOf(value);
            java.math.BigDecimal rounded = exact.setScale(0, java.math.RoundingMode.HALF_UP);
            // Remove only a binary representation tail; preserve a meaningful fraction
            // so the normal integer validation can report it to the user.
            if (exact.subtract(rounded).abs().compareTo(new java.math.BigDecimal("0.000000001")) <= 0)
                return rounded.toPlainString();
            return normalizedDecimal(value, 3);
        }
        return numberText(cell, 3);
    }

    private static String numberText(Cell cell, int scale) {
        if (cell == null) return "";
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            // Excel stores numeric cells as binary doubles. Normalize the representational
            // tail (for example 86.49999999999999) to the import/storage precision.
            return normalizedDecimal(value, scale);
        }
        String raw = text(cell).replace(",", "");
        return raw.matches("[+-]?(?:\\d+)(?:\\.\\d+)?") ? raw : "";
    }

    private static String decimalText(Cell cell, int scale) {
        if (cell == null) return "";
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type != CellType.NUMERIC) return numberText(cell, scale);
        double value = cell.getNumericCellValue();
        return normalizedDecimal(value, scale);
    }

    private static String normalizedDecimal(double value, int scale) {
        return Double.isFinite(value) ? java.math.BigDecimal.valueOf(value)
            .setScale(scale, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() : "";
    }

    private static Map<Integer, List<Image>> readImages(MultipartFile file, XSSFWorkbook workbook,
                                                         List<ProductImportPreviewRow> rows, boolean supplier) throws Exception {
        Map<String, byte[]> entries = readZipEntries(file);
        Map<String, Image> cellImages = readWpsCellImages(entries);
        Map<String, List<LocatedImage>> byLocation = new HashMap<>();
        for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
            XSSFSheet sheet = workbook.getSheetAt(s);
            XSSFDrawing drawing = sheet.getDrawingPatriarch();
            if (drawing != null) {
                String name = drawing.getPackagePart().getPartName().getName().replaceFirst("^/", "");
                addDrawingImages(entries, name, s, byLocation);
            }
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row excelRow = sheet.getRow(r);
                if (excelRow == null) continue;
                String formula = text(excelRow.getCell(0));
                Matcher id = IMAGE_ID.matcher(formula);
                if (formula.contains("DISPIMG") && id.find()) {
                    Image image = cellImages.get(id.group());
                    if (image != null) add(byLocation, s, r + 1, 0, 0, 0, 0,
                        image.data(), image.extension(), image.description());
                }
            }
        }
        Map<Integer, List<Image>> result = new LinkedHashMap<>();
        for (ProductImportPreviewRow row : rows) {
            int sheet = row.getSourceSheetIndex();
            int physicalRow = row.getSourceRowIndex() > 0 ? row.getSourceRowIndex() : row.getRowIndex();
            row.setSourceSheetIndex(sheet);
            row.setSourceRowIndex(physicalRow);
            row.setSourceSheetName(workbook.getSheetAt(sheet).getSheetName());
            XSSFSheet sourceSheet = workbook.getSheetAt(sheet);
            CellRangeAddress sharedPictureCell = null;
            for (CellRangeAddress region : supplier ? sourceSheet.getMergedRegions() : List.<CellRangeAddress>of()) {
                if (region.getFirstColumn() == 0 && region.getLastColumn() == 0
                    && region.getFirstRow() < region.getLastRow()
                    && physicalRow >= region.getFirstRow() + 1 && physicalRow <= region.getLastRow() + 1) {
                    sharedPictureCell = region;
                    break;
                }
            }
            List<LocatedImage> located = new ArrayList<>();
            if (sharedPictureCell == null) {
                located.addAll(byLocation.getOrDefault(sheet + ":" + physicalRow, List.of()));
            } else {
                for (int pictureRow = sharedPictureCell.getFirstRow() + 1;
                     pictureRow <= sharedPictureCell.getLastRow() + 1; pictureRow++) {
                    located.addAll(byLocation.getOrDefault(sheet + ":" + pictureRow, List.of()));
                }
            }
            located.sort(Comparator.comparingInt(LocatedImage::row)
                .thenComparingLong(LocatedImage::rowOffset)
                .thenComparingInt(LocatedImage::column)
                .thenComparingLong(LocatedImage::columnOffset)
                .thenComparingInt(LocatedImage::ordinal));
            Set<String> describedCodes = located.stream().map(position -> leadingCode(position.image().description()))
                .filter(code -> !code.isBlank()).collect(Collectors.toSet());
            if (sharedPictureCell != null && describedCodes.size() > 1) {
                row.getImageWarnings().add("合并图片区含多个商品编码的图片说明，已作为共用图片列出；请核对并取消不属于本商品的图片");
            }
            List<Image> accepted = new ArrayList<>();
            String code = row.getFields().getOrDefault("externalCode", "");
            boolean hasMatchingImage = located.stream().anyMatch(position ->
                descriptionMatchesCode(position.image().description(), code));
            Set<String> mismatchedDescriptions = new java.util.LinkedHashSet<>();
            for (LocatedImage position : located) {
                Image image = position.image();
                if (sharedPictureCell == null && !descriptionMatchesCode(image.description(), code)) {
                    mismatchedDescriptions.add(image.description());
                    if (hasMatchingImage) continue;
                }
                if (accepted.stream().noneMatch(existing -> Arrays.equals(existing.data(), image.data()))) {
                    accepted.add(image);
                }
            }
            if (!mismatchedDescriptions.isEmpty()) {
                if (hasMatchingImage) {
                    row.getImageWarnings().add("本行另有图片说明编码与商品编码不一致，已排除："
                        + String.join("、", mismatchedDescriptions));
                } else {
                    // The cell position is credible, but a copied image description may be stale.
                    // Show these pictures for review; require the user to opt in to each one.
                    row.setSelectedEmbeddedImageIndexes(new ArrayList<>());
                    row.getImageWarnings().add("图片位于本商品行，但内部说明编码不一致（"
                        + String.join("、", mismatchedDescriptions) + "）；请逐张勾选确认归属后再导入");
                }
            }
            row.setEmbeddedImageCount(accepted.size());
            if (!accepted.isEmpty()) result.put(row.getRowIndex(), accepted);
        }
        return result;
    }

    private static void add(Map<String, List<LocatedImage>> locations, int sheet, int row,
                            long rowOffset, int column, long columnOffset, int ordinal, byte[] data,
                            String extension, String description) {
        String ext = extension.toLowerCase(Locale.ROOT);
        if (data.length == 0 || data.length > MAX_IMAGE_BYTES || !List.of("png", "jpg", "jpeg", "webp").contains(ext)) return;
        locations.computeIfAbsent(sheet + ":" + row, ignored -> new ArrayList<>())
            .add(new LocatedImage(new Image(data, ext, description == null ? "" : description),
                row, rowOffset, column, columnOffset, ordinal));
    }

    private static boolean descriptionMatchesCode(String description, String code) {
        if (description == null || description.isBlank() || code == null || code.isBlank()) return true;
        String value = description.trim();
        if (leadingCode(value).isBlank()) return true;
        if (value.equalsIgnoreCase(code)) return true;
        if (value.regionMatches(true, 0, code, 0, code.length())
            && (value.length() == code.length() || !isAsciiCodeChar(value.charAt(code.length())))) return true;
        Matcher range = Pattern.compile("([A-Za-z0-9]*?)(\\d+)-(\\d+)").matcher(value);
        if (range.matches()) {
            String first = range.group(1) + range.group(2);
            String last = range.group(1) + String.format("%0" + range.group(2).length() + "d", Integer.parseInt(range.group(3)));
            return code.equalsIgnoreCase(first) || code.equalsIgnoreCase(last);
        }
        for (String token : value.split("[^A-Za-z0-9]+")) if (code.equalsIgnoreCase(token)) return true;
        return false;
    }

    private static String leadingCode(String description) {
        if (description == null) return "";
        Matcher match = DESCRIPTION_CODE.matcher(description.trim());
        return match.find() ? match.group().toUpperCase(Locale.ROOT) : "";
    }

    private static boolean isAsciiCodeChar(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'
            || value >= '0' && value <= '9';
    }

    private static Map<String, byte[]> readZipEntries(MultipartFile file) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String name = entry.getName();
                if (name.equals("xl/cellimages.xml") || name.equals("xl/_rels/cellimages.xml.rels")
                    || name.startsWith("xl/media/") || name.startsWith("xl/drawings/")) {
                    int max = name.startsWith("xl/media/") ? MAX_IMAGE_BYTES : 5 * 1024 * 1024;
                    byte[] data = zip.readNBytes(max + 1);
                    if (data.length <= max) entries.put(name, data);
                }
            }
        }
        return entries;
    }

    private static void addDrawingImages(Map<String, byte[]> entries, String drawingName, int sheet,
                                         Map<String, List<LocatedImage>> locations) throws Exception {
        byte[] xml = entries.get(drawingName);
        String base = drawingName.substring(0, drawingName.lastIndexOf('/') + 1);
        String leaf = drawingName.substring(drawingName.lastIndexOf('/') + 1);
        byte[] relXml = entries.get(base + "_rels/" + leaf + ".rels");
        if (xml == null || relXml == null) return;
        Map<String, String> relations = relations(relXml, base);
        Node root = parseXml(xml).getDocumentElement();
        int ordinal = 0;
        for (Node anchor = root.getFirstChild(); anchor != null; anchor = anchor.getNextSibling()) {
            if (!(anchor instanceof Element element) || !element.getLocalName().endsWith("Anchor")) continue;
            NodeList starts = element.getElementsByTagNameNS("*", "from");
            if (starts.getLength() == 0) continue;
            NodeList rowNodes = ((Element) starts.item(0)).getElementsByTagNameNS("*", "row");
            if (rowNodes.getLength() == 0) continue;
            int row;
            try { row = Integer.parseInt(rowNodes.item(0).getTextContent()) + 1; }
            catch (NumberFormatException ignored) { continue; }
            Element from = (Element) starts.item(0);
            long rowOffset = coordinate(from, "rowOff");
            int column = (int) coordinate(from, "col");
            long columnOffset = coordinate(from, "colOff");
            NodeList pics = element.getElementsByTagNameNS("*", "pic");
            for (int i = 0; i < pics.getLength(); i++) {
                Element pic = (Element) pics.item(i);
                NodeList blips = pic.getElementsByTagNameNS("*", "blip");
                if (blips.getLength() == 0) continue;
                String media = relations.get(((Element) blips.item(0)).getAttributeNS(REL_NS, "embed"));
                byte[] data = entries.get(media);
                if (data == null) continue;
                NodeList props = pic.getElementsByTagNameNS("*", "cNvPr");
                String description = props.getLength() > 0
                    ? ((Element) props.item(0)).getAttribute("descr") : "";
                add(locations, sheet, row, rowOffset, column, columnOffset, ordinal++, data,
                    media.substring(media.lastIndexOf('.') + 1), description);
            }
        }
    }

    private static long coordinate(Element from, String name) {
        NodeList values = from.getElementsByTagNameNS("*", name);
        if (values.getLength() == 0) return 0;
        try { return Long.parseLong(values.item(0).getTextContent()); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static Map<String, String> relations(byte[] relXml, String base) throws Exception {
        Map<String, String> relations = new HashMap<>();
        NodeList rels = parseXml(relXml).getElementsByTagNameNS("*", "Relationship");
        for (int i = 0; i < rels.getLength(); i++) {
            Element e = (Element) rels.item(i);
            if (e.getAttribute("TargetMode").equalsIgnoreCase("External")) continue;
            String target = Path.of(base).resolve(e.getAttribute("Target")).normalize().toString().replace('\\', '/');
            if (target.startsWith("xl/media/")) relations.put(e.getAttribute("Id"), target);
        }
        return relations;
    }

    private static Map<String, Image> readWpsCellImages(Map<String, byte[]> entries) throws Exception {
        byte[] xml = entries.get("xl/cellimages.xml");
        byte[] relXml = entries.get("xl/_rels/cellimages.xml.rels");
        if (xml == null || relXml == null) return Map.of();
        Map<String, String> relations = relations(relXml, "xl/");
        Map<String, Image> result = new HashMap<>();
        NodeList pics = parseXml(xml).getElementsByTagNameNS("*", "pic");
        for (int i = 0; i < pics.getLength(); i++) {
            Element pic = (Element) pics.item(i);
            NodeList props = pic.getElementsByTagNameNS("*", "cNvPr");
            NodeList blips = pic.getElementsByTagNameNS("*", "blip");
            if (props.getLength() == 0 || blips.getLength() == 0) continue;
            Element prop = (Element) props.item(0);
            String media = relations.get(((Element) blips.item(0)).getAttributeNS(REL_NS, "embed"));
            byte[] data = entries.get(media);
            if (data == null) continue;
            String ext = media.substring(media.lastIndexOf('.') + 1);
            if (data.length > 0 && data.length <= MAX_IMAGE_BYTES && List.of("png", "jpg", "jpeg", "webp").contains(ext.toLowerCase(Locale.ROOT)))
                result.put(prop.getAttribute("name"), new Image(data, ext, prop.getAttribute("descr")));
        }
        return result;
    }

    private static org.w3c.dom.Document parseXml(byte[] data) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(data));
    }
}
