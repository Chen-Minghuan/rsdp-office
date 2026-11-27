package com.rsdp.officeproduct.importer.office.product;

import com.rsdp.officeproduct.importer.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.UnsupportedFileFormatException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.rsdp.officeproduct.importer.office.product.LocalProductModels.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class LocalProductWorkbook {
    private static final int MAX_WORKBOOK_MB = 150;
    private static final long MAX_WORKBOOK_BYTES = MAX_WORKBOOK_MB * 1024L * 1024L;
    private static final String FACTORY_COLUMN = "供应厂编码";
    private static final List<String> REQUIRED = List.of("商品名称", "分类编码", "厂内型号", "规格名称", "宽(mm)", "深(mm)");
    private final LocalProductService products;
    private final WorkbookPreviewCache previewCache;

    public record RowError(int row, String message, String sheet, String column) {
        RowError(int row, String message) { this(row, message, null, null); }
    }
    public record Source(String sheet, int row, String column) {
        Source(String sheet, int row, int column) {
            this(sheet, row + 1, CellReference.convertNumToColString(column));
        }
        RowError issue(String message) { return new RowError(row, message, sheet, column); }
    }
    public record ImageCandidate(String key, boolean selected) {}
    public enum MaterialSource { COLUMN, DESCRIPTION }
    public record VariantEvidence(String description, Source descriptionCell, MaterialSource materialSource) {}
    public record SupplierProduct(Input product, List<Source> sources, List<ImageCandidate> images, String sourceCategory,
                                  List<VariantEvidence> variantEvidence) {}
    public record SupplierPreview(List<SupplierProduct> products, Map<String, ImageInput> images) {}
    public record Preview(List<Input> products, List<RowError> errors, SupplierPreview supplier,
                          List<SupplierWorkbookMapping.Sheet> mappingSheets, UUID previewId, Map<String, Integer> imageSizes) {
        Preview(List<Input> products, List<RowError> errors, SupplierPreview supplier, List<SupplierWorkbookMapping.Sheet> mappingSheets) {
            this(products, errors, supplier, mappingSheets, null, Map.of());
        }
        Preview(List<Input> products, List<RowError> errors) { this(products, errors, null, List.of()); }
    }

    public byte[] template() throws IOException {
        products.requireWriter();
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("本地商品");
            var header = sheet.createRow(0);
            var fields = new ArrayList<>(REQUIRED);
            fields.addAll(List.of("高(mm)", "尺寸原文", "颜色", "材质", "说明"));
            for (int i = 0; i < fields.size(); i++) {
                header.createCell(i).setCellValue(fields.get(i));
                sheet.setColumnWidth(i, 4608);
            }
            sheet.createFreezePane(0, 1);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    public Preview preview(MultipartFile file) {
        return preview(file, null);
    }

    public Preview preview(MultipartFile file, SupplierWorkbookMapping.Selection mapping) {
        return preview(file, mapping, false);
    }

    public Preview preview(MultipartFile file, SupplierWorkbookMapping.Selection mapping, boolean imageReferences) {
        var actor = products.requireWriter();
        String workspace = actor.getWorkspaceId();
        String boundFactory = products.boundFactory(workspace);
        if (file.isEmpty()) throw new BusinessException("请选择要导入的 .xlsx 文件");
        if (file.getSize() > MAX_WORKBOOK_BYTES) {
            throw new BusinessException("Excel 文件不能超过 " + MAX_WORKBOOK_MB + " MB");
        }
        Path upload = null;
        try {
            upload = Files.createTempFile("office-product-workbook-", ".xlsx");
            file.transferTo(upload);
            try (var cached = imageReferences ? previewCache.open(actor, workbookSource(file)) : null;
                 var archive = OPCPackage.open(upload.toFile(), PackageAccess.READ);
                 var workbook = new XSSFWorkbook(archive)) {
                var preview = previewWorkbook(workbook, workspace, boundFactory, mapping, cached);
                if (cached == null) return preview;
                var sizes = cached.sizes();
                boolean hasProducts = !preview.products().isEmpty()
                    || preview.supplier() != null && !preview.supplier().products().isEmpty();
                boolean keepPreview = hasProducts || !sizes.isEmpty();
                if (keepPreview) cached.keep();
                return new Preview(preview.products(), preview.errors(), preview.supplier(), preview.mappingSheets(),
                    keepPreview ? cached.id() : null, sizes);
            }
        } catch (IOException | InvalidFormatException | POIXMLException | UnsupportedFileFormatException e) {
            throw new BusinessException("文件无法读取，请提供有效的 .xlsx 工作簿");
        } finally {
            if (upload != null) {
                try {
                    Files.deleteIfExists(upload);
                } catch (IOException e) {
                    log.warn("本地商品 Excel 临时文件清理失败", e);
                }
            }
        }
    }

    private WorkbookPreviewCache.WorkbookSource workbookSource(MultipartFile file) {
        String original = file.getOriginalFilename();
        String path = original == null ? "" : original.replace('\\', '/');
        String name = path.substring(path.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (name.isBlank()) name = "Excel 工作簿.xlsx";
        int nameLength = Math.min(255, name.codePointCount(0, name.length()));
        return new WorkbookPreviewCache.WorkbookSource(name.substring(0, name.offsetByCodePoints(0, nameLength)), file.getSize());
    }

    private Preview previewWorkbook(XSSFWorkbook workbook, String workspace, String boundFactory, SupplierWorkbookMapping.Selection mapping,
                                    WorkbookPreviewCache.Session cached)
            throws IOException, InvalidFormatException {
        if (workbook.getNumberOfSheets() == 0) throw new BusinessException("工作簿没有工作表");
        var sheet = workbook.getSheetAt(0);
        var header = sheet.getRow(0);
        var formatter = new DataFormatter();
        formatter.setUseCachedValuesForFormulaCells(true);
        if (mapping != null) return new SupplierProductWorkbook(workbook, formatter).preview(mapping, cached);
        var columns = new HashMap<String, Integer>();
        if (header != null) header.forEach(cell -> columns.put(formatter.formatCellValue(cell).strip(), cell.getColumnIndex()));
        columns.remove(FACTORY_COLUMN);
        var missing = REQUIRED.stream().filter(name -> !columns.containsKey(name)).toList();
        if (!missing.isEmpty()) {
            var supplier = new SupplierProductWorkbook(workbook, formatter).preview(null, cached);
            if (supplier != null) return supplier;
            var mappingSheets = SupplierWorkbookMapping.inspect(workbook, formatter);
            if (!mappingSheets.isEmpty()) return new Preview(List.of(), List.of(), null, mappingSheets);
            throw new BusinessException("未识别到供应商原表表头。Office 模板缺少表头 " + String.join("、", missing));
        }
        if (sheet.getLastRowNum() > 500) throw new BusinessException("每次最多 500 行");

        var pictures = new HashMap<Integer, List<XSSFPicture>>();
        var drawing = sheet.getDrawingPatriarch();
        if (drawing != null) {
            for (var shape : drawing.getShapes()) {
                if (shape instanceof XSSFPicture picture && picture.getClientAnchor() != null) {
                    pictures.computeIfAbsent(picture.getClientAnchor().getRow1(), key -> new ArrayList<>()).add(picture);
                }
            }
        }
        var groups = new LinkedHashMap<List<String>, Input>();
        var errors = new ArrayList<RowError>();
        var knownModels = new HashMap<List<String>, Boolean>();
        long totalImageBytes = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            var row = sheet.getRow(i);
            if (row == null) continue;
            Map<String, String> cells = new HashMap<>();
            columns.forEach((key, column) -> cells.put(key, formatter.formatCellValue(row.getCell(column)).strip()));
            if (cells.values().stream().allMatch(String::isBlank)) continue;
            try {
                List<ImageInput> images = readPictures(pictures.getOrDefault(i, List.of()));
                String material = WorkbookMaterial.explicit(cells.getOrDefault("材质", ""));
                if (material.isBlank()) {
                    String description = cells.entrySet().stream()
                        .filter(cell -> WorkbookMaterial.DESCRIPTION_HEADERS.contains(SupplierProductWorkbook.label(cell.getKey())))
                        .map(Map.Entry::getValue).filter(value -> !value.isBlank()).findFirst().orElse("");
                    material = WorkbookMaterial.fromDescription(WorkbookMaterial.description(description));
                }
                var variant = new Variant(null, cells.get("规格名称"), decimal(cells.get("宽(mm)")),
                    decimal(cells.get("深(mm)")), decimal(cells.get("高(mm)")), cells.get("尺寸原文"),
                    cells.get("颜色"), material);
                Input item = products.normalize(new Input(null, null, cells.get("商品名称"), cells.get("分类编码"),
                    null, cells.get("厂内型号"), List.of(variant), images), boundFactory);
                List<String> key = List.of(item.factoryCode(), item.factoryModel());
                if (knownModels.computeIfAbsent(key, k -> products.modelExists(workspace, k.getFirst(), k.getLast()))) {
                    throw new BusinessException("本地已有商品，请在本地商品中编辑");
                }
                Input old = groups.get(key);
                if (cached == null && old == null && groups.size() >= MAX_PRODUCTS) throw new BusinessException("每批最多 100 个商品");
                if (old != null && (!old.name().equals(item.name()) || !old.categoryCode().equals(item.categoryCode()))) {
                    throw new BusinessException("同型号的商品名称或分类不一致");
                }
                List<Variant> variants = new ArrayList<>(old == null ? List.of() : old.variants());
                Variant normalized = item.variants().getFirst();
                if (variants.contains(normalized)) throw new BusinessException("同型号的规格重复");
                variants.add(normalized);
                List<ImageInput> combinedImages = new ArrayList<>(old == null ? List.of() : old.images());
                combinedImages.addAll(images);
                if (variants.size() > MAX_VARIANTS || combinedImages.size() > MAX_IMAGES) {
                    throw new BusinessException("每个商品最多 30 个规格和 6 张图片");
                }
                long rowBytes = images.stream().mapToLong(image -> image.content().length).sum();
                if (cached == null && totalImageBytes + rowBytes > MAX_BATCH_IMAGE_BYTES) {
                    throw new BusinessException("每批图片合计不可超过 12 MB");
                }
                totalImageBytes += rowBytes;
                if (cached != null) {
                    for (int index = combinedImages.size() - images.size(); index < combinedImages.size(); index++) {
                        combinedImages.set(index, cached.store(combinedImages.get(index)));
                    }
                }
                groups.put(key, new Input(null, null, item.name(), item.categoryCode(), item.factoryCode(),
                    item.factoryModel(), variants, combinedImages));
            } catch (WorkbookPreviewCache.Limit error) {
                throw error;
            } catch (BusinessException error) {
                errors.add(new RowError(i + 1, error.getMessage()));
            }
        }
        return new Preview(List.copyOf(groups.values()), errors);
    }

    private List<ImageInput> readPictures(List<XSSFPicture> pictures) throws IOException {
        if (pictures.size() > MAX_IMAGES) throw new BusinessException("每个商品最多 6 张图片");
        var images = new ArrayList<ImageInput>();
        for (XSSFPicture picture : pictures) {
            var data = picture.getPictureData();
            try (var input = data.getPackagePart().getInputStream()) {
                byte[] content = input.readNBytes(MAX_IMAGE_BYTES + 1);
                if (content.length > MAX_IMAGE_BYTES) throw new BusinessException("单张图片不可超过 2 MB");
                images.add(new ImageInput(null, data.getMimeType(), content));
            }
        }
        return images;
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new BusinessException("宽、深、高须填写毫米数值");
        }
    }
}
