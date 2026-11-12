package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.ImportProperties;
import com.rsdp.officeproduct.importer.dto.excel.ProductImportRow;
import com.rsdp.officeproduct.importer.dto.request.ManualProductEntryRequest;
import com.rsdp.officeproduct.importer.dto.request.ProductImportRequest;
import com.rsdp.officeproduct.importer.dto.response.CategoryDictDto;
import com.rsdp.officeproduct.importer.dto.response.ProductImportFailure;
import com.rsdp.officeproduct.importer.dto.response.ProductImportPreview;
import com.rsdp.officeproduct.importer.dto.response.ProductImportPreviewRow;
import com.rsdp.officeproduct.importer.dto.response.ProductImportResult;
import com.rsdp.officeproduct.importer.util.DictNormalizes;
import com.rsdp.officeproduct.importer.util.EmbeddedImagePreview;
import com.rsdp.officeproduct.importer.util.EmbeddedImageStore;
import com.rsdp.officeproduct.importer.util.ExcelFileValidator;
import com.rsdp.officeproduct.importer.util.ImportRowCodec;
import com.rsdp.officeproduct.importer.util.SupplierProductAdapter;
import com.rsdp.officeproduct.importer.util.WorkbookImportSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 商品导入服务：office 侧负责 Excel/CSV 解析、清洗、预览与人机确认；
 * 确认后的商品行转发写入主项目（RSDP 主库，归属服务账号本厂身份），office 本地不存商品。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImportService {

    private static final int MAX_ROWS = 500;

    private final DictService dictService;
    private final DictAliasService dictAliasService;
    private final ImportProperties importProperties;
    private final DataCleansingService dataCleansingService;
    private final ObjectMapper objectMapper;
    private final EmbeddedImageStore embeddedImageStore;
    private final MainProductEntryGateway entryGateway;

    private static final List<String> SHARED_FIELDS = List.of(
        "productName", "colorPrimaryName", "description", "retailPrice");

    /** 上传解析和预检，不写入商品。 */
    public ProductImportPreview previewProducts(MultipartFile file) {
        validateFile(file);
        ProductImportRequest request = new ProductImportRequest();
        WorkbookImportSource.Source source = WorkbookImportSource.read(file);
        request.setRows(source.rows());
        ProductImportPreview preview = prepare(request).preview();
        int previewChars = 0;
        final int maxPreviewChars = 20 * 1024 * 1024;
        for (ProductImportPreviewRow row : preview.getRows()) {
            for (WorkbookImportSource.Image image : source.images().getOrDefault(row.getRowIndex(), List.of())) {
                if (row.getImagePreviews().size() >= 8) {
                    row.getImageWarnings().add("该行图片超过 8 张，预览仅展示前 8 张；确认导入仍处理全部关联图片");
                    break;
                }
                String thumbnail = EmbeddedImagePreview.create(image);
                if (thumbnail == null) row.getImageWarnings().add("有内嵌图片无法生成预览，请在导入后核对原图");
                else if (previewChars + thumbnail.length() > maxPreviewChars) {
                    row.getImageWarnings().add("预览图片总量已达上限，剩余图片请在导入后核对");
                    break;
                } else {
                    row.getImagePreviews().add(thumbnail);
                    previewChars += thumbnail.length();
                }
            }
        }
        preview.setEmbeddedImageCount(preview.getRows().stream().mapToInt(ProductImportPreviewRow::getEmbeddedImageCount).sum());
        preview.setRowsWithImages((int) preview.getRows().stream().filter(r -> r.getEmbeddedImageCount() > 0).count());
        return preview;
    }

    /** 校验用户编辑后的数据；跳过行不参与分组、继承或校验。 */
    public ProductImportPreview validatePreview(ProductImportRequest request) {
        ProductImportPreview preview = prepare(request).preview();
        preview.setEmbeddedImageCount(preview.getRows().stream().mapToInt(ProductImportPreviewRow::getEmbeddedImageCount).sum());
        preview.setRowsWithImages((int) preview.getRows().stream().filter(r -> r.getEmbeddedImageCount() > 0).count());
        return preview;
    }

    public ProductImportResult importProducts(MultipartFile file, boolean updateIfExists) {
        validateFile(file);
        ProductImportRequest request = new ProductImportRequest();
        WorkbookImportSource.Source source = WorkbookImportSource.read(file);
        request.setRows(source.rows());
        request.setUpdateIfExists(updateIfExists);
        return importRows(request, source);
    }

    public ProductImportResult importRowsWithFile(MultipartFile file, ProductImportRequest request) {
        validateFile(file);
        WorkbookImportSource.Source source = WorkbookImportSource.read(file);
        Map<Integer, ProductImportPreviewRow> originals = new HashMap<>();
        for (ProductImportPreviewRow r : source.rows()) {
            originals.put(r.getRowIndex(), r);
        }
        if (request == null || request.getRows() == null || request.getRows().size() != originals.size())
            throw new BusinessException("预览数据与上传文件不一致，请重新预览");
        for (ProductImportPreviewRow row : request.getRows()) {
            if (row == null || row.getFields() == null) throw new BusinessException("商品行数据不完整，请重新预览");
            ProductImportPreviewRow original = originals.get(row.getRowIndex());
            if (original == null || row.getSourceSheetIndex() != original.getSourceSheetIndex()
                || row.getSourceRowIndex() != original.getSourceRowIndex())
                throw new BusinessException("商品行位置与上传文件不一致，请重新预览");
            String oldCode = original.getFields().getOrDefault("externalCode", "");
            String newCode = row.getFields().getOrDefault("externalCode", "");
            if (!row.isSkipped() && !oldCode.isBlank() && !oldCode.equals(newCode)
                && source.images().containsKey(row.getRowIndex()))
                throw new BusinessException("第 " + row.getRowIndex() + " 行商品编码已修改，无法确认内嵌图片归属；请恢复原编码或跳过该行");
            row.setSourceSheetName(original.getSourceSheetName());
            row.setSourceSizeText(original.getSourceSizeText());
            row.setSourceVariantCount(original.getSourceVariantCount());
            row.setSourceVariantNote(original.getSourceVariantNote());
            row.setEmbeddedImageCount(original.getEmbeddedImageCount());
            row.setImageWarnings(new ArrayList<>(original.getImageWarnings()));
            Integer primaryIndex = row.getPrimaryEmbeddedImageIndex();
            if (primaryIndex != null && (primaryIndex < 0 || primaryIndex >= original.getEmbeddedImageCount()))
                throw new BusinessException("第 " + row.getRowIndex() + " 行主图选择无效，请重新预览");
            List<Integer> selectedIndexes = row.getSelectedEmbeddedImageIndexes();
            if (selectedIndexes != null) {
                if (selectedIndexes.stream().anyMatch(index -> index == null || index < 0 || index >= original.getEmbeddedImageCount())
                    || new HashSet<>(selectedIndexes).size() != selectedIndexes.size())
                    throw new BusinessException("第 " + row.getRowIndex() + " 行图片选择无效，请重新预览");
                if (primaryIndex != null && !selectedIndexes.contains(primaryIndex))
                    throw new BusinessException("第 " + row.getRowIndex() + " 行主图未包含在已选图片中");
            }
        }
        Map<String, ProductImportPreviewRow> sharedSourceImages = new HashMap<>();
        for (ProductImportPreviewRow row : request.getRows()) {
            if (row.getSourceVariantCount() < 2) continue;
            String sourceKey = row.getSourceSheetIndex() + ":" + row.getSourceRowIndex();
            ProductImportPreviewRow first = sharedSourceImages.putIfAbsent(sourceKey, row);
            if (first != null && (!Objects.equals(first.getSelectedEmbeddedImageIndexes(), row.getSelectedEmbeddedImageIndexes())
                || !Objects.equals(first.getPrimaryEmbeddedImageIndex(), row.getPrimaryEmbeddedImageIndex())))
                throw new BusinessException("同一来源行拆分出的规格必须共用相同的图片选择和主图，请重新预览");
        }
        return importRows(request, source);
    }

    /** 同商品多行只建一次主档；每个规格行单独转发提交，失败不占用分组状态。 */
    public synchronized ProductImportResult importRows(ProductImportRequest request) {
        return importRows(request, null);
    }

    private synchronized ProductImportResult importRows(ProductImportRequest request, WorkbookImportSource.Source workbook) {
        PreparedImport prepared = prepare(request);
        ProductImportResult result = new ProductImportResult();
        result.setTotalRows(prepared.preview().getRows().size());
        result.setCleansingReport(prepared.preview().getCleansingReport());
        Map<String, String> committedGroups = new HashMap<>();
        int variantCount = 0;
        int imageCount = 0;
        for (ProductImportPreviewRow source : prepared.preview().getRows()) {
            ProductImportRow row = prepared.rows().get(source.getRowIndex());
            for (String warning : source.getImageWarnings()) {
                result.getWarnings().add(new ProductImportFailure(source.getRowIndex(), row.getExternalCode(),
                    row.getRspuId(), warning));
            }
            if (source.isSkipped()) {
                result.setSkippedCount(result.getSkippedCount() + 1);
                continue;
            }
            if (!source.getErrors().isEmpty()) {
                result.getFailures().add(new ProductImportFailure(source.getRowIndex(), row.getExternalCode(),
                    row.getRspuId(), String.join("；", source.getErrors())));
                continue;
            }
            try {
                String committedRspuId = committedGroups.get(source.getGroupKey());
                String existingRspuId = committedRspuId != null ? committedRspuId
                    : entryGateway.findRspuIdByExternalCode(row.getExternalCode());
                if (existingRspuId != null && committedRspuId == null && !request.isUpdateIfExists()) {
                    result.setSkippedCount(result.getSkippedCount() + 1);
                    result.getWarnings().add(new ProductImportFailure(source.getRowIndex(), row.getExternalCode(),
                        existingRspuId, "产品已存在，已跳过"));
                    continue;
                }
                List<EmbeddedImageStore.Saved> written = new ArrayList<>();
                MainProductEntryGateway.EntryResult outcome;
                try {
                    if (workbook != null) {
                        appendEmbeddedImages(source, row,
                            workbook.images().getOrDefault(source.getRowIndex(), List.of()), written);
                    }
                    outcome = forwardRow(row, existingRspuId, prepared.dictCache());
                } catch (Exception e) {
                    written.forEach(embeddedImageStore::deleteIfCreated);
                    throw e;
                }
                committedGroups.put(source.getGroupKey(), outcome.rspuId());
                variantCount++;
                imageCount += outcome.imageCount();
                result.setSuccessCount(result.getSuccessCount() + 1);
            } catch (Exception e) {
                log.warn("导入行失败，rowIndex={}", source.getRowIndex(), e);
                result.getFailures().add(new ProductImportFailure(source.getRowIndex(), row.getExternalCode(),
                    row.getRspuId(), e instanceof BusinessException ? e.getMessage() : "系统异常，请稍后重试"));
            }
        }
        result.setProductCount(committedGroups.size());
        result.setVariantCount(variantCount);
        result.setEmbeddedImageCount(imageCount);
        result.setFailedCount(result.getFailures().size());
        return result;
    }

    /**
     * 单行转发：新品走主项目 factory-entry（建主档+变体+报价+图片）；
     * 更新模式（已有商品）更新基础字段 + 补变体 + 补报价。
     */
    private MainProductEntryGateway.EntryResult forwardRow(ProductImportRow row, String existingRspuId,
                                                           Map<String, List<CategoryDictDto>> dictCache) {
        MainProductEntryGateway.EntryPayload payload = toPayload(row, dictCache);
        if (existingRspuId == null) {
            return entryGateway.forwardFactoryEntry(payload);
        }
        entryGateway.updateBaseFields(existingRspuId, payload);
        String variantId = resolveVariantId(existingRspuId, row, dictCache);
        if (variantId == null) {
            variantId = entryGateway.addVariant(existingRspuId, payload);
        }
        String rskuId = entryGateway.addQuote(existingRspuId, variantId, payload);
        return new MainProductEntryGateway.EntryResult(existingRspuId, variantId, rskuId, null, 0);
    }

    /** 更新模式下匹配已有变体：先按显示名，再按 尺寸+颜色+材质 签名。 */
    private String resolveVariantId(String rspuId, ProductImportRow row, Map<String, List<CategoryDictDto>> dictCache) {
        var variants = entryGateway.listVariants(rspuId);
        if (!variants.isArray()) {
            return null;
        }
        String targetName = trim(row.getVariantDisplayName());
        String targetSignature = variantSignature(row, dictCache);
        String fallback = null;
        for (var variant : variants) {
            if (!"active".equals(variant.path("status").asText("active"))) continue;
            String displayName = variant.path("displayName").asText("");
            if (targetName != null && targetName.equals(displayName)) {
                return variant.path("variantId").asText();
            }
            String signature = writeJsonListOrNull(List.of(
                variant.path("sizeText").asText(variant.path("sizeCode").asText("")),
                variant.path("colorText").asText(variant.path("colorCode").asText("")),
                variant.path("materialText").asText(variant.path("materialCode").asText(""))));
            if (signature.equals(targetSignature)) {
                fallback = variant.path("variantId").asText();
            }
        }
        return fallback;
    }

    /** 行 → 转发载荷（字典码未归一时尝试用原文归一，仍失败则原文随 text 字段透传）。 */
    private MainProductEntryGateway.EntryPayload toPayload(ProductImportRow row, Map<String, List<CategoryDictDto>> dictCache) {
        List<String> imageUrls = new ArrayList<>();
        if (StringUtils.hasText(row.getPrimaryImageUrl())) imageUrls.add(row.getPrimaryImageUrl().trim());
        imageUrls.addAll(splitCsv(row.getDetailImageUrls()));
        return new MainProductEntryGateway.EntryPayload(
            trim(row.getExternalCode()),
            trim(row.getProductName()),
            trim(row.getColorPrimaryName()),
            trim(row.getDescription()),
            row.getRetailPrice(),
            trim(row.getVariantDisplayName()),
            resolveCodeOrNull("size", row.getSizeCode(), dictCache.get("size")),
            StringUtils.hasText(row.getSizeText()) ? row.getSizeText().trim() : null,
            row.getWidthMm(), row.getDepthMm(), row.getHeightMm(),
            resolveCodeOrNull("color", row.getColorCode(), dictCache.get("color")),
            StringUtils.hasText(row.getColorText()) ? row.getColorText().trim() : null,
            // 主项目要求变体主材质码必填：材质码缺失时用材质原文再归一一次，仍失败用默认材质码（板式）
            entryGateway.effectiveMaterialCode(
                firstNonNull(resolveCodeOrNull("material", row.getMaterialCode(), dictCache.get("material")),
                    resolveCodeOrNull("material", row.getMaterialText(), dictCache.get("material")))),
            StringUtils.hasText(row.getMaterialText()) ? row.getMaterialText().trim() : null,
            row.getFactoryPrice(),
            row.getMoq(),
            row.getLeadTimeDays(),
            imageUrls);
    }

    private String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }

    private void appendEmbeddedImages(ProductImportPreviewRow source, ProductImportRow row,
                                      List<WorkbookImportSource.Image> images, List<EmbeddedImageStore.Saved> written) {
        List<Integer> selectedIndexes = source.getSelectedEmbeddedImageIndexes();
        for (int imageIndex = 0; imageIndex < images.size(); imageIndex++) {
            if (selectedIndexes != null && !selectedIndexes.contains(imageIndex)) continue;
            EmbeddedImageStore.Saved saved = embeddedImageStore.save(images.get(imageIndex));
            written.add(saved);
            if (Objects.equals(source.getPrimaryEmbeddedImageIndex(), imageIndex)) {
                if (StringUtils.hasText(row.getPrimaryImageUrl()) && !row.getPrimaryImageUrl().equals(saved.url())) {
                    String previous = row.getDetailImageUrls();
                    row.setDetailImageUrls(StringUtils.hasText(previous) ? previous + "," + row.getPrimaryImageUrl() : row.getPrimaryImageUrl());
                }
                row.setPrimaryImageUrl(saved.url());
            } else if (!StringUtils.hasText(row.getPrimaryImageUrl())) row.setPrimaryImageUrl(saved.url());
            else if (!row.getPrimaryImageUrl().equals(saved.url())) {
                String previous = row.getDetailImageUrls();
                row.setDetailImageUrls(StringUtils.hasText(previous) ? previous + "," + saved.url() : saved.url());
            }
        }
    }

    private record PreparedImport(ProductImportPreview preview, Map<Integer, ProductImportRow> rows,
                                  Map<String, List<CategoryDictDto>> dictCache) { }

    private PreparedImport prepare(ProductImportRequest request) {
        if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
            throw new BusinessException("没有可导入的商品行");
        }
        if (request.getRows().size() > MAX_ROWS) throw new BusinessException("单次导入不能超过 " + MAX_ROWS + " 行");
        ProductImportPreview preview = new ProductImportPreview();
        preview.setColumns(ImportRowCodec.columns());
        Map<String, List<CategoryDictDto>> dicts = preloadDicts();
        Map<Integer, ProductImportRow> typed = new LinkedHashMap<>();
        Map<String, List<ProductImportPreviewRow>> groups = new LinkedHashMap<>();
        Map<String, String> existingGroups = new HashMap<>();
        Set<Integer> rowNumbers = new HashSet<>();
        for (ProductImportPreviewRow input : request.getRows()) {
            if (input == null || input.getFields() == null || input.getRowIndex() < 2
                || !rowNumbers.add(input.getRowIndex())) throw new BusinessException("行号无效或重复");
            ProductImportPreviewRow source = new ProductImportPreviewRow();
            source.setRowIndex(input.getRowIndex());
            source.setSourceSheetIndex(input.getSourceSheetIndex());
            source.setSourceRowIndex(input.getSourceRowIndex());
            source.setSourceSheetName(input.getSourceSheetName());
            source.setSourceSizeText(input.getSourceSizeText());
            source.setSourceVariantCount(input.getSourceVariantCount());
            source.setSourceVariantNote(input.getSourceVariantNote());
            source.setEmbeddedImageCount(input.getEmbeddedImageCount());
            source.setPrimaryEmbeddedImageIndex(input.getPrimaryEmbeddedImageIndex());
            source.setSelectedEmbeddedImageIndexes(input.getSelectedEmbeddedImageIndexes() == null ? null
                : new ArrayList<>(input.getSelectedEmbeddedImageIndexes()));
            source.setImageWarnings(new ArrayList<>(input.getImageWarnings() == null ? List.of() : input.getImageWarnings()));
            source.setReviewWarnings(new ArrayList<>());
            source.setFields(new LinkedHashMap<>(input.getFields()));
            source.setSkipped(input.isSkipped());
            source.setGroupKey(input.getGroupKey());
            preview.getRows().add(source);
            ProductImportRow row = ImportRowCodec.decode(source);
            typed.put(source.getRowIndex(), row);
            if (source.isSkipped()) {
                source.getErrors().clear();
                source.setAction("手动跳过");
                preview.setSkippedRows(preview.getSkippedRows() + 1);
                continue;
            }
            dataCleansingService.cleanse(row, source.getRowIndex(), preview.getCleansingReport());
            normalizeRow(row, dicts);
            ImportRowCodec.encode(row, source);
            String existingRspuId = entryGateway.findRspuIdByExternalCode(row.getExternalCode());
            String key = existingRspuId != null ? "id:" + existingRspuId
                : StringUtils.hasText(row.getExternalCode()) ? "code:" + row.getExternalCode()
                : "row:" + source.getRowIndex();
            source.setGroupKey(key);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(source);
            if (existingRspuId != null) existingGroups.put(key, existingRspuId);
        }
        for (var entry : groups.entrySet()) {
            List<ProductImportPreviewRow> members = entry.getValue();
            String existingRspuId = existingGroups.get(entry.getKey());
            Map<String, String> shared = new HashMap<>();
            Set<String> conflicts = new LinkedHashSet<>();
            for (ProductImportPreviewRow member : members) {
                for (String field : SHARED_FIELDS) {
                    String value = member.getFields().get(field);
                    if (StringUtils.hasText(value)) {
                        String previous = shared.putIfAbsent(field, value);
                        if (previous != null && !previous.equals(value)) conflicts.add(field);
                    }
                }
            }
            Set<String> variants = new HashSet<>();
            for (ProductImportPreviewRow member : members) {
                for (String field : SHARED_FIELDS) {
                    if (!StringUtils.hasText(member.getFields().get(field)) && shared.containsKey(field)) {
                        member.getFields().put(field, shared.get(field));
                    }
                }
                for (String conflict : conflicts) {
                    String label = preview.getColumns().stream().filter(c -> c.key().equals(conflict))
                        .map(ProductImportPreview.Column::label).findFirst().orElse(conflict);
                    member.getErrors().add("同一商品的“" + label + "”不一致，请统一或修改外部编码");
                }
                ProductImportPreviewRow decodeSource = new ProductImportPreviewRow();
                decodeSource.setFields(member.getFields());
                ProductImportRow row = ImportRowCodec.decode(decodeSource);
                row.setSourceSizeText(member.getSourceSizeText());
                for (String decodeError : decodeSource.getErrors()) {
                    if (!member.getErrors().contains(decodeError)) member.getErrors().add(decodeError);
                }
                normalizeRow(row, dicts);
                String error = validateRow(row);
                if (error != null) member.getErrors().add(error);
                if (member.getSourceRowIndex() > 0) {
                    int[] exact = SupplierProductAdapter.exactDimensions(row.getSizeText());
                    if (exact != null && ((row.getWidthMm() != null && !row.getWidthMm().equals(exact[0]))
                        || (row.getDepthMm() != null && !row.getDepthMm().equals(exact[1]))
                        || (row.getHeightMm() != null && !row.getHeightMm().equals(exact[2]))))
                        member.getErrors().add("结构化尺寸与三段尺寸原文不一致，请核对毫米值");
                }
                if (member.getErrors().isEmpty() && (existingRspuId == null || request.isUpdateIfExists())) {
                    String signature = variantSignature(row, dicts);
                    if (!variants.add(signature)) member.getErrors().add("同一商品存在重复规格（尺寸、颜色、材质），请合并或跳过重复行");
                }
                ImportRowCodec.encode(row, member);
                addReviewWarnings(member, row);
                typed.put(member.getRowIndex(), row);
                member.setAction(existingRspuId != null ? (request.isUpdateIfExists() ? "更新已有商品" : "跳过已有商品")
                    : members.indexOf(member) == 0 ? "新增商品" : "新增规格");
                if (!member.getErrors().isEmpty()) preview.setInvalidRows(preview.getInvalidRows() + 1);
            }
            if (members.stream().anyMatch(r -> r.getErrors().isEmpty())
                && (existingRspuId == null || request.isUpdateIfExists())) preview.setProductCount(preview.getProductCount() + 1);
        }
        return new PreparedImport(preview, typed, dicts);
    }

    private void addReviewWarnings(ProductImportPreviewRow member, ProductImportRow row) {
        if (member.getSourceRowIndex() <= 0) return;
        List<String> warnings = member.getReviewWarnings();
        if (member.getSourceVariantCount() > 1) {
            warnings.add("原行尺寸“" + member.getSourceSizeText() + "”已拆为 " + member.getSourceVariantCount()
                + " 个规格；当前规格为 " + row.getSizeText());
            if (StringUtils.hasText(member.getSourceVariantNote())) warnings.add(member.getSourceVariantNote());
        }
        if (row.getWidthMm() == null || row.getDepthMm() == null)
            warnings.add("缺少确定的宽度和进深，不能用于精确尺寸匹配；请核对规格后填写毫米值");
        else if (row.getHeightMm() == null)
            warnings.add("高度未确定；宽度和进深可用于占地尺寸匹配");
        if (row.getRetailPrice() == null) warnings.add("缺少 J 列价格，请核对展示价");
        if (row.getFactoryPrice() == null) warnings.add("缺少 L 列出厂价格");
        if (member.getEmbeddedImageCount() == 0) warnings.add("未关联内嵌图片，请核对图片归属或稍后补图");
    }

    private String variantSignature(ProductImportRow row, Map<String, List<CategoryDictDto>> dicts) {
        String size = effectiveOf(resolveCodeOrNull("size", row.getSizeCode(), dicts.get("size")), row.getSizeText());
        return writeJsonListOrNull(List.of(
            size.isEmpty() ? rowDimensionKey(row) : size,
            effectiveOf(resolveCodeOrNull("color", row.getColorCode(), dicts.get("color")), row.getColorText()),
            effectiveOf(resolveCodeOrNull("material", row.getMaterialCode(), dicts.get("material")), row.getMaterialText())));
    }

    private String rowDimensionKey(ProductImportRow row) {
        return String.valueOf(row.getWidthMm()) + "/" + String.valueOf(row.getDepthMm()) + "/" + String.valueOf(row.getHeightMm());
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        long maxBytes = importProperties.getMaxFileSize().toBytes();
        if (file.getSize() > maxBytes) {
            throw new BusinessException("文件大小不能超过 " + importProperties.getMaxFileSize().toMegabytes() + "MB");
        }
        if (!ExcelFileValidator.isExcelOrCsv(file)) {
            throw new BusinessException("仅支持 Excel (.xlsx/.xls) 或 CSV 文件");
        }
    }

    private Map<String, List<CategoryDictDto>> preloadDicts() {
        Map<String, List<CategoryDictDto>> cache = new HashMap<>();
        cache.put("material", safeList(dictService.listByType("material")));
        cache.put("size", safeList(dictService.listByType("size")));
        cache.put("color", safeList(dictService.listByType("color")));
        return cache;
    }

    private List<CategoryDictDto> safeList(List<CategoryDictDto> list) {
        return list == null ? List.of() : list;
    }

    private String resolveCodeOrNull(String dictType, String raw, List<CategoryDictDto> dicts) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String byAlias = dictAliasService.resolveAlias(dictType, raw.trim());
        if (StringUtils.hasText(byAlias)) {
            return byAlias;
        }
        String normalized = DictNormalizes.normalize(raw.trim(), dicts);
        return DictNormalizes.isValid(normalized, dicts) ? normalized : null;
    }

    private String effectiveOf(String code, String text) {
        if (StringUtils.hasText(code)) {
            return code.trim();
        }
        return StringUtils.hasText(text) ? text.trim() : "";
    }

    private String validateRow(ProductImportRow row) {
        if (!StringUtils.hasText(row.getProductName())) return "商品名称不能为空";
        if (row.getFactoryPrice() != null && (row.getFactoryPrice().signum() < 0
            || row.getFactoryPrice().compareTo(new BigDecimal("9999999999.99")) > 0)) return "工厂价必须在 0~9999999999.99 之间";
        if (row.getRetailPrice() != null && (row.getRetailPrice().signum() < 0
            || row.getRetailPrice().compareTo(new BigDecimal("9999999999.99")) > 0)) return "零售参考价必须在 0~9999999999.99 之间";
        if (row.getMoq() != null && row.getMoq() < 1) return "MOQ 必须大于等于 1";
        if (row.getLeadTimeDays() != null && row.getLeadTimeDays() < 0) return "交期天数不能为负数";
        for (String url : splitCsv(row.getDetailImageUrls())) {
            if (!validImageUrl(url)) return "详情图 URL 必须是有效的 http/https 地址且不超过 500 字符";
        }
        if (StringUtils.hasText(row.getPrimaryImageUrl()) && !validImageUrl(row.getPrimaryImageUrl())) return "主图 URL 必须是有效的 http/https 地址且不超过 500 字符";
        if (StringUtils.hasText(row.getSizeText()) && row.getSizeText().length() > 100) return "尺寸原文不能超过 100 字符";
        if (row.getPackageCount() != null && (row.getPackageCount() < 1 || row.getPackageCount() > 100000))
            return "包数必须在 1~100000 之间";
        if (row.getPackageVolumeCbm() != null && (row.getPackageVolumeCbm().signum() < 0
            || row.getPackageVolumeCbm().compareTo(new BigDecimal("999999999.999999999")) > 0
            || row.getPackageVolumeCbm().stripTrailingZeros().scale() > 9))
            return "体积(CBM)必须在 0~999999999.999999999 之间，最多 9 位小数";
        if (row.getGrossWeight() != null && (row.getGrossWeight().signum() < 0
            || row.getGrossWeight().compareTo(new BigDecimal("99999999999.999")) > 0
            || row.getGrossWeight().stripTrailingZeros().scale() > 3))
            return "重量必须在 0~99999999999.999 之间，最多 3 位小数";
        for (Integer value : List.of(row.getWidthMm() == null ? 1 : row.getWidthMm(),
            row.getDepthMm() == null ? 1 : row.getDepthMm(), row.getHeightMm() == null ? 1 : row.getHeightMm())) {
            if (value < 1 || value > 100000) return "结构化尺寸必须在 1~100000 mm 之间";
        }
        if ((row.getWidthMm() == null) != (row.getDepthMm() == null))
            return "宽度和进深必须同时填写毫米值";
        if (StringUtils.hasText(row.getColorText()) && row.getColorText().length() > 100) return "颜色原文不能超过 100 字符";
        if (StringUtils.hasText(row.getMaterialText()) && row.getMaterialText().length() > 255) return "材质原文不能超过 255 字符";
        if (StringUtils.hasText(row.getExternalCode()) && row.getExternalCode().trim().length() > 64) {
            return "外部编码 长度不能超过 64";
        }
        if (StringUtils.hasText(row.getProductName()) && row.getProductName().trim().length() > 255) {
            return "商品名称 长度不能超过 255";
        }
        if (StringUtils.hasText(row.getVariantDisplayName()) && row.getVariantDisplayName().trim().length() > 128) {
            return "变体显示名称 长度不能超过 128";
        }

        return null;
    }

    private boolean validImageUrl(String value) {
        try {
            var uri = java.net.URI.create(value);
            // office 本地上传的内嵌图片是 /uploads/images/ 相对路径，转发时从磁盘读取
            if (value.startsWith("/uploads/")) return value.length() <= 500;
            return value.length() <= 500 && ("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null;
        } catch (IllegalArgumentException e) { return false; }
    }

    // ==================== 手工录入 ====================

    /** 手工录入：校验后转发主项目 factory-entry（落主库、归属本厂）。 */
    public Map<String, Object> createManualEntry(ManualProductEntryRequest request) {
        ProductImportRow row = toImportRow(request);
        Map<String, List<CategoryDictDto>> dictCache = preloadDicts();
        normalizeRow(row, dictCache);
        String error = validateRow(row);
        if (error != null) throw new BusinessException(error);

        MainProductEntryGateway.EntryResult result = entryGateway.forwardFactoryEntry(toPayload(row, dictCache));
        Map<String, Object> response = new HashMap<>();
        response.put("rspuId", result.rspuId());
        response.put("variantId", result.variantId());
        response.put("message", "手工录入产品成功");
        return response;
    }

    private ProductImportRow toImportRow(ManualProductEntryRequest request) {
        ProductImportRow row = new ProductImportRow();
        row.setProductName(request.getProductName());
        row.setColorPrimaryName(request.getColorPrimaryName());
        row.setDescription(request.getDescription());
        row.setRetailPrice(request.getRetailPrice());
        row.setExternalCode(request.getExternalCode());
        row.setVariantDisplayName(request.getVariantDisplayName());
        row.setSizeCode(request.getSizeCode());
        row.setSizeText(request.getSizeText());
        row.setWidthMm(request.getWidthMm());
        row.setDepthMm(request.getDepthMm());
        row.setHeightMm(request.getHeightMm());
        row.setColorCode(request.getColorCode());
        row.setColorText(request.getColorText());
        row.setMaterialCode(request.getMaterialCode());
        row.setMaterialText(request.getMaterialText());
        row.setFactoryPrice(request.getFactoryPrice());
        row.setMoq(request.getMoq());
        row.setLeadTimeDays(request.getLeadTimeDays());

        List<String> urls = request.getImageUrls() == null ? List.of() : request.getImageUrls();
        if (!urls.isEmpty()) {
            row.setPrimaryImageUrl(urls.get(0));
            row.setDetailImageUrls(urls.size() > 1 ? String.join(",", urls.subList(1, urls.size())) : null);
        }
        return row;
    }

    private void normalizeRow(ProductImportRow row, Map<String, List<CategoryDictDto>> dictCache) {
        row.setRspuId(trim(row.getRspuId()));
        row.setExternalCode(trim(row.getExternalCode()));
        row.setProductName(trim(row.getProductName()));
        row.setDescription(trim(row.getDescription()));
        row.setColorPrimaryName(trim(row.getColorPrimaryName()));
        row.setPrimaryImageUrl(trim(row.getPrimaryImageUrl()));
        row.setDetailImageUrls(trim(row.getDetailImageUrls()));
        row.setVariantDisplayName(trim(row.getVariantDisplayName()));
        row.setSizeCode(trim(row.getSizeCode()));
        row.setColorCode(trim(row.getColorCode()));
        row.setMaterialCode(trim(row.getMaterialCode()));

        if (StringUtils.hasText(row.getSizeText())) row.setSizeText(row.getSizeText().trim());
        if (StringUtils.hasText(row.getColorText())) row.setColorText(row.getColorText().trim());
        if (StringUtils.hasText(row.getMaterialText())) row.setMaterialText(row.getMaterialText().trim());
        if (StringUtils.hasText(row.getSizeCode()) && resolveCodeOrNull("size", row.getSizeCode(), dictCache.get("size")) == null
            && !StringUtils.hasText(row.getSizeText())) row.setSizeText(row.getSizeCode());
        if (StringUtils.hasText(row.getColorCode()) && resolveCodeOrNull("color", row.getColorCode(), dictCache.get("color")) == null
            && !StringUtils.hasText(row.getColorText())) row.setColorText(row.getColorCode());
        if (StringUtils.hasText(row.getMaterialCode()) && resolveCodeOrNull("material", row.getMaterialCode(), dictCache.get("material")) == null
            && !StringUtils.hasText(row.getMaterialText())) row.setMaterialText(row.getMaterialCode());
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private List<String> splitCsv(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            if (StringUtils.hasText(part)) result.add(part.trim());
        }
        return result;
    }

    private String writeJsonListOrNull(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return null;
        }
    }
}
