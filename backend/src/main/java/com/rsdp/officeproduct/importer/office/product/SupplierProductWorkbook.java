package com.rsdp.officeproduct.importer.office.product;

import com.rsdp.officeproduct.importer.common.BusinessException;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.rsdp.officeproduct.importer.office.product.LocalProductModels.*;
import static com.rsdp.officeproduct.importer.office.product.LocalProductWorkbook.*;
import static com.rsdp.officeproduct.importer.office.product.SupplierWorkbookMapping.Field.*;

final class SupplierProductWorkbook {
    private static final Set<String> MODEL_HEADERS = Set.of("产品编号", "商品编号", "itemno", "itemnumber");
    private static final Set<String> NAME_HEADERS = Set.of("产品名称", "商品名称", "itemname");
    private static final Set<String> PICTURE_HEADERS = Set.of("产品图形", "产品图片", "商品图片", "图片", "picture");
    private static final Set<String> SIZE_HEADERS = Set.of("规格", "尺寸", "规格长x宽x高", "尺寸长x宽x高", "spec", "size", "dimensions");
    private static final Set<String> COLOR_HEADERS = Set.of("颜色", "商品颜色", "产品颜色", "color", "colour");
    private static final Set<String> MATERIAL_HEADERS = Set.of("材质", "产品材质", "商品材质", "主要材质", "材料", "material", "materials");
    private static final Set<String> CATEGORY_HEADERS = Set.of("分类编码", "分类", "品类编码", "商品分类", "品类", "categorycode", "category");
    private static final Pattern MODEL_CODE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._/-]{0,63}");
    private static final Pattern PICTURE_DESCRIPTION_CODE = Pattern.compile("^([a-zA-Z0-9]+(?:[-_][a-zA-Z0-9]+)*)");
    private static final Pattern RANGE = Pattern.compile("^([a-zA-Z0-9]*[a-zA-Z])(\\d{1,5})-(\\d{1,5})$");
    private static final String DIMENSION = "(\\d{1,6}(?:\\.\\d{1,2})?)";
    private static final String TIMES = "\\s*[xX×*]\\s*";
    private static final String MILLIMETRES = "\\s*(?:mm|毫米)?\\s*";
    private static final Pattern THREE = Pattern.compile("^" + DIMENSION + TIMES + DIMENSION + "(?:" + TIMES + DIMENSION + ")?" + MILLIMETRES + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TWO_WIDTHS = Pattern.compile("^" + DIMENSION + "\\s*[/／]\\s*" + DIMENSION + TIMES + DIMENSION + TIMES + DIMENSION + MILLIMETRES + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TWO_HEIGHTS = Pattern.compile("^" + DIMENSION + TIMES + DIMENSION + TIMES + DIMENSION + "\\s*[/／]\\s*" + DIMENSION + MILLIMETRES + "$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SINGLE_DIMENSION = Pattern.compile("^" + DIMENSION + MILLIMETRES + "$", Pattern.CASE_INSENSITIVE);

    private record Columns(int model, int name, int picture, int size, int width, int depth, int height,
                           int color, int material, int category, int description) {
        boolean hasSize() { return size >= 0 || width >= 0 || depth >= 0; }
    }
    private record Entry(Source source, String model, String name, Row row, Columns columns) {}
    private record LocatedRow(Entry entry, Map<String, Boolean> pictures, String description, Source descriptionCell) {}
    private record CatalogSection(List<Entry> rows, int start, int end) {}

    private final XSSFWorkbook workbook;
    private final DataFormatter formatter;
    private final List<RowError> errors = new ArrayList<>();
    private int sourceRows;

    SupplierProductWorkbook(XSSFWorkbook workbook, DataFormatter formatter) {
        this.workbook = workbook;
        this.formatter = formatter;
    }

    Preview preview(SupplierWorkbookMapping.Selection mapping, WorkbookPreviewCache.Session cached) throws IOException, InvalidFormatException {
        var catalog = new LinkedHashMap<XSSFSheet, List<CatalogSection>>();
        if (mapping != null) {
            mapping.validate(workbook);
            var sheet = workbook.getSheetAt(mapping.sheetIndex());
            catalog.put(sheet, mappedSections(sheet, mapping));
        } else {
            for (var sheet : workbook) {
                var sections = sections((XSSFSheet) sheet);
                if (!sections.isEmpty()) catalog.put((XSSFSheet) sheet, sections);
            }
        }
        if (catalog.isEmpty()) return null;
        var pictures = new SupplierWorkbookImages(workbook, cached);
        var products = new LinkedHashMap<String, List<LocatedRow>>();
        for (var sheet : catalog.entrySet()) {
            var anchors = pictures.anchors(sheet.getKey());
            var merges = sheet.getKey().getMergedRegions();
            for (var section : sheet.getValue()) {
                for (var entry : section.rows()) {
                    var candidates = new LinkedHashMap<String, Boolean>();
                    int photo = entry.columns().picture();
                    if (photo >= 0) {
                        var merge = merges.stream().filter(range -> range.isInRange(entry.source().row() - 1, photo))
                            .findFirst().orElse(null);
                        int row = entry.source().row() - 1;
                        int first = merge == null ? row : Math.max(section.start(), merge.getFirstRow());
                        int last = merge == null ? row : Math.min(section.end(), merge.getLastRow());
                        try {
                            var cell = pictures.cellPicture(sheet.getKey(), first, photo);
                            if (cell != null) addPicture(candidates, pictures, cell, entry, true);
                        } catch (WorkbookPreviewCache.Limit error) {
                            throw error;
                        } catch (BusinessException error) {
                            errors.add(entry.source().issue(error.getMessage()));
                        }
                        for (var anchor : anchors) {
                            if (anchor.column() != photo || anchor.row() < section.start() || anchor.row() > section.end()) continue;
                            boolean nearby = first <= anchor.row() && anchor.row() <= last;
                            addPicture(candidates, pictures, anchor.picture(), entry, nearby);
                        }
                    }
                    int descriptionColumn = entry.columns().description();
                    int descriptionRow = descriptionColumn < 0 ? entry.row().getRowNum()
                        : mergedDescriptionRow(merges, entry.row().getRowNum(), descriptionColumn, section.start());
                    String description = WorkbookMaterial.description(text(sheet.getKey().getRow(descriptionRow), descriptionColumn));
                    Source descriptionCell = description.isEmpty() ? null
                        : new Source(entry.source().sheet(), descriptionRow, descriptionColumn);
                    products.computeIfAbsent(entry.model(), key -> new ArrayList<>())
                        .add(new LocatedRow(entry, candidates, description, descriptionCell));
                }
            }
        }
        if (products.isEmpty()) throw new BusinessException("已识别供应商表头，未找到可预览的商品行");
        return new Preview(List.of(), errors,
            new SupplierPreview(products.values().stream().map(this::product).toList(), pictures.includedImages()), List.of());
    }

    private int mergedDescriptionRow(List<CellRangeAddress> merges, int row, int column, int firstDataRow) {
        return merges.stream().filter(merge -> merge.getFirstColumn() == column && merge.getLastColumn() == column
                && row >= merge.getFirstRow() && row <= merge.getLastRow() && merge.getFirstRow() >= firstDataRow)
            .map(CellRangeAddress::getFirstRow).findFirst().orElse(row);
    }

    private List<CatalogSection> sections(XSSFSheet sheet) {
        var sections = new ArrayList<CatalogSection>();
        List<Columns> columns = List.of();
        List<Entry> entries = new ArrayList<>();
        int start = 0;
        for (Row row : sheet) {
            var header = header(row);
            if (!header.isEmpty()) {
                if (!columns.isEmpty() && !entries.isEmpty()) {
                    sections.add(new CatalogSection(entries, start, row.getRowNum() - 1));
                    entries = new ArrayList<>();
                }
                columns = header;
                start = row.getRowNum() + 1;
                continue;
            }
            for (Columns block : columns) {
                appendEntry(sheet, entries, row, block);
            }
        }
        if (!columns.isEmpty()) sections.add(new CatalogSection(entries, start, sheet.getLastRowNum()));
        return sections;
    }

    private List<CatalogSection> mappedSections(XSSFSheet sheet, SupplierWorkbookMapping.Selection mapping) {
        var block = new Columns(mapping.column(MODEL), mapping.column(NAME), mapping.column(PICTURE), mapping.column(SIZE),
            mapping.column(WIDTH_MM), mapping.column(DEPTH_MM), mapping.column(HEIGHT_MM), mapping.column(COLOR),
            mapping.column(MATERIAL), mapping.column(CATEGORY), mapping.column(DESCRIPTION));
        var header = sheet.getRow(mapping.headerRow() - 1);
        var sections = new ArrayList<CatalogSection>();
        var entries = new ArrayList<Entry>();
        int start = mapping.headerRow();
        for (Row row : sheet) {
            if (row.getRowNum() < start) continue;
            if (label(text(row, block.model())).equals(label(text(header, block.model())))
                    && label(text(row, block.name())).equals(label(text(header, block.name())))) {
                if (!entries.isEmpty()) sections.add(new CatalogSection(entries, start, row.getRowNum() - 1));
                entries = new ArrayList<>();
                start = row.getRowNum() + 1;
                continue;
            }
            appendEntry(sheet, entries, row, block);
        }
        sections.add(new CatalogSection(entries, start, sheet.getLastRowNum()));
        return sections;
    }

    private void appendEntry(XSSFSheet sheet, List<Entry> entries, Row row, Columns block) {
        String model = text(row, block.model());
        String name = text(row, block.name());
        if (model.isEmpty() && name.isEmpty()) return;
        if (++sourceRows > 500) throw new BusinessException("每次最多 500 条商品来源行");
        var source = new Source(sheet.getSheetName(), row.getRowNum(), block.model());
        if (!MODEL_CODE.matcher(model).matches() || name.isEmpty()) {
            errors.add(source.issue("缺少有效的产品编号或产品名称"));
            return;
        }
        entries.add(new Entry(source, model, name, row, block));
    }

    private List<Columns> header(Row row) {
        var codes = new ArrayList<Integer>();
        for (var cell : row) {
            int column = cell.getColumnIndex();
            if (MODEL_HEADERS.contains(label(text(row, column))) && NAME_HEADERS.contains(label(text(row, column + 1)))) {
                codes.add(column);
            }
        }
        var result = new ArrayList<Columns>();
        for (int index = 0; index < codes.size(); index++) {
            int code = codes.get(index);
            int end = index + 1 == codes.size() ? row.getLastCellNum() : codes.get(index + 1) - 1;
            int photo = PICTURE_HEADERS.contains(label(text(row, code - 1))) ? code - 1 : -1;
            result.add(new Columns(code, code + 1, photo, field(row, code + 2, end, SIZE_HEADERS), -1, -1, -1,
                field(row, code + 2, end, COLOR_HEADERS), field(row, code + 2, end, MATERIAL_HEADERS),
                field(row, code + 2, end, CATEGORY_HEADERS), field(row, code + 2, end, WorkbookMaterial.DESCRIPTION_HEADERS)));
        }
        return result;
    }

    private int field(Row row, int from, int end, Set<String> names) {
        for (int column = from; column < end; column++) {
            if (names.contains(label(text(row, column)))) return column;
        }
        return -1;
    }

    private String text(Row row, int column) {
        return row == null || column < 0 ? "" : formatter.formatCellValue(row.getCell(column)).strip();
    }

    static String label(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s.()（）]", "").replace('×', 'x');
    }

    private void addPicture(Map<String, Boolean> candidates, SupplierWorkbookImages pictures,
                            SupplierWorkbookImages.Picture picture, Entry entry, boolean nearby) throws IOException {
        var code = PICTURE_DESCRIPTION_CODE.matcher(picture.description().strip());
        boolean hasCode = code.find() && code.group().length() >= 5 && code.group().chars().anyMatch(Character::isDigit);
        boolean matches = hasCode && matchesCode(code.group(), entry.model());
        if (!nearby && !matches) return;
        try {
            String key = pictures.include(picture);
            candidates.merge(key, !hasCode || matches, Boolean::logicalOr);
        } catch (WorkbookPreviewCache.Limit error) {
            throw error;
        } catch (BusinessException error) {
            errors.add(entry.source().issue(error.getMessage()));
        }
    }

    private static boolean matchesCode(String description, String model) {
        if (description.equalsIgnoreCase(model)) return true;
        var range = RANGE.matcher(description);
        if (!range.matches() || range.group(2).length() != range.group(3).length()) return false;
        if (!model.regionMatches(true, 0, range.group(1), 0, range.group(1).length())) return false;
        String suffix = model.substring(range.group(1).length());
        if (suffix.length() != range.group(2).length() || !suffix.matches("\\d{1,5}")) return false;
        int number = Integer.parseInt(suffix);
        return number >= Integer.parseInt(range.group(2)) && number <= Integer.parseInt(range.group(3));
    }

    private SupplierProduct product(List<LocatedRow> rows) {
        // A price-only layout can repeat a model already described in the main catalog.
        List<LocatedRow> detailed = rows.stream().filter(r -> r.entry().columns().hasSize()).toList();
        List<LocatedRow> specifications = detailed.isEmpty() ? rows : detailed;
        var first = specifications.getFirst().entry();
        var variants = new LinkedHashMap<Variant, VariantEvidence>();
        for (var row : specifications) {
            var entry = row.entry();
            String explicit = WorkbookMaterial.explicit(text(entry.row(), entry.columns().material()));
            String material = explicit.isEmpty() ? WorkbookMaterial.fromDescription(row.description()) : explicit;
            MaterialSource source = !explicit.isEmpty() ? MaterialSource.COLUMN : material.isEmpty() ? null : MaterialSource.DESCRIPTION;
            var evidence = new VariantEvidence(row.description(), row.descriptionCell(), source);
            for (Variant variant : variants(entry, material)) {
                variants.merge(variant, evidence, (firstEvidence, next) -> firstEvidence.description().isEmpty() ? next : firstEvidence);
            }
        }
        var candidates = new LinkedHashMap<String, Boolean>();
        for (var row : specifications) row.pictures().forEach((key, selected) -> candidates.merge(key, selected, Boolean::logicalOr));
        for (var row : rows) row.pictures().forEach((key, selected) -> candidates.merge(key, selected, Boolean::logicalOr));
        var images = new ArrayList<ImageCandidate>();
        int selected = 0;
        for (var candidate : candidates.entrySet()) {
            boolean include = candidate.getValue() && selected < MAX_IMAGES;
            images.add(new ImageCandidate(candidate.getKey(), include));
            if (include) selected++;
        }
        var input = new Input(null, null, first.name(), "", "", first.model(), List.copyOf(variants.keySet()), List.of());
        var categories = rows.stream().map(LocatedRow::entry).map(entry -> text(entry.row(), entry.columns().category()))
            .filter(value -> !value.isBlank()).distinct().toList();
        if (categories.size() > 1) errors.add(first.source().issue("同型号在原表有多个品类，请核对"));
        return new SupplierProduct(input, rows.stream().map(r -> r.entry().source()).toList(), images,
            categories.size() == 1 ? categories.getFirst() : null, List.copyOf(variants.values()));
    }

    private List<Variant> variants(Entry entry, String material) {
        var columns = entry.columns();
        String raw = text(entry.row(), columns.size()).replaceAll("\\s+", " ").strip();
        String width = dimensionText(entry, columns.width());
        String depth = dimensionText(entry, columns.depth());
        String height = dimensionText(entry, columns.height());
        if (width != null && depth != null) return List.of(variant(entry, raw, width, depth, height, material));
        var alternatives = TWO_WIDTHS.matcher(raw);
        if (alternatives.matches()) {
            return List.of(variant(entry, raw, alternatives.group(1), alternatives.group(3), alternatives.group(4), material),
                variant(entry, raw, alternatives.group(2), alternatives.group(3), alternatives.group(4), material));
        }
        var exact = THREE.matcher(raw);
        if (exact.matches()) return List.of(variant(entry, raw, exact.group(1), exact.group(2), exact.group(3), material));
        var heights = TWO_HEIGHTS.matcher(raw);
        if (heights.matches()) return List.of(variant(entry, raw, heights.group(1), heights.group(2), null, material));
        return List.of(variant(entry, raw, width, depth, height, material));
    }

    private String dimensionText(Entry entry, int column) {
        var match = SINGLE_DIMENSION.matcher(text(entry.row(), column));
        return match.matches() ? match.group(1) : null;
    }

    private Variant variant(Entry entry, String raw, String width, String depth, String height, String material) {
        String name = width == null || depth == null ? "默认规格"
            : String.join("×", height == null ? List.of(width, depth) : List.of(width, depth, height));
        return new Variant(null, name, mm(width), mm(depth), mm(height), raw,
            text(entry.row(), entry.columns().color()), material);
    }

    private BigDecimal mm(String value) {
        return value == null ? null : new BigDecimal(value).stripTrailingZeros();
    }
}
