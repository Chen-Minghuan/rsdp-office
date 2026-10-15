package com.rsdp.officeproduct.importer.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.FactoryProperties;
import com.rsdp.officeproduct.importer.config.ImportProperties;
import com.rsdp.officeproduct.importer.dto.excel.ProductImportRow;
import com.rsdp.officeproduct.importer.dto.request.ManualProductEntryRequest;
import com.rsdp.officeproduct.importer.dto.request.ProductImportRequest;
import com.rsdp.officeproduct.importer.dto.response.ProductDetailDto;
import com.rsdp.officeproduct.importer.dto.response.ProductImportPreview;
import com.rsdp.officeproduct.importer.dto.response.ProductImportPreviewRow;
import com.rsdp.officeproduct.importer.dto.response.ProductImportFailure;
import com.rsdp.officeproduct.importer.dto.response.ProductImportResult;
import com.rsdp.officeproduct.importer.entity.*;
import com.rsdp.officeproduct.importer.mapper.*;
import com.rsdp.officeproduct.importer.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImportService {

    private static final int MAX_ROWS = 500;

    private final RspuMasterMapper rspuMasterMapper;
    private final RspuVariantMapper rspuVariantMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final FactoryProductPriceMapper factoryProductPriceMapper;
    private final DictService dictService;
    private final DictAliasService dictAliasService;
    private final RspuCodeService rspuCodeService;
    private final FactoryProperties factoryProperties;
    private final ImportProperties importProperties;
    private final DataCleansingService dataCleansingService;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    private final EmbeddedImageStore embeddedImageStore;

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

    /** 兼容原文件导入接口，与预览确认使用同一处理逻辑。 */
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
        Map<Integer, ProductImportPreviewRow> originals = source.rows().stream()
            .collect(Collectors.toMap(ProductImportPreviewRow::getRowIndex, r -> r));
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

    /** 同商品多行只建一次主档；每个规格行单独提交，失败不占用分组状态。 */
    public synchronized ProductImportResult importRows(ProductImportRequest request) {
        return importRows(request, null);
    }

    private synchronized ProductImportResult importRows(ProductImportRequest request, WorkbookImportSource.Source workbook) {
        PreparedImport prepared = prepare(request);
        ProductImportResult result = new ProductImportResult();
        result.setTotalRows(prepared.preview().getRows().size());
        result.setCleansingReport(prepared.preview().getCleansingReport());
        Map<String, String> committedGroups = new HashMap<>();
        Set<String> committedVariants = new HashSet<>();
        Set<String> committedImageAssociations = new HashSet<>();
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
                String committedId = committedGroups.get(source.getGroupKey());
                RspuMaster existing = committedId != null ? rspuMasterMapper.selectById(committedId)
                    : resolveExistingRspu(row.getRspuId(), row.getExternalCode());
                if (existing != null && committedId == null && !request.isUpdateIfExists()) {
                    result.setSkippedCount(result.getSkippedCount() + 1);
                    result.getWarnings().add(new ProductImportFailure(source.getRowIndex(), row.getExternalCode(),
                        existing.getRspuId(), "产品已存在，已跳过"));
                    continue;
                }
                List<EmbeddedImageStore.Saved> written = new ArrayList<>();
                RowOutcome outcome;
                try {
                    if (workbook != null) {
                        appendEmbeddedImages(source, row,
                            workbook.images().getOrDefault(source.getRowIndex(), List.of()), written);
                    }
                    outcome = processRowInTransaction(row, existing, prepared.dictCache(), committedId != null);
                } catch (Exception e) {
                    written.forEach(embeddedImageStore::deleteIfCreated);
                    throw e;
                }
                committedGroups.put(source.getGroupKey(), outcome.rspuId());
                if (outcome.variantId() != null) committedVariants.add(outcome.variantId());
                result.setSuccessCount(result.getSuccessCount() + 1);
                for (EmbeddedImageStore.Saved saved : written)
                    committedImageAssociations.add(outcome.rspuId() + "\0" + saved.url());
            } catch (Exception e) {
                log.warn("导入行失败，rowIndex={}", source.getRowIndex(), e);
                result.getFailures().add(new ProductImportFailure(source.getRowIndex(), row.getExternalCode(),
                    row.getRspuId(), e instanceof BusinessException ? e.getMessage() : "系统异常，请稍后重试"));
            }
        }
        result.setProductCount(committedGroups.size());
        result.setVariantCount(committedVariants.size());
        result.setEmbeddedImageCount(committedImageAssociations.size());
        result.setFailedCount(result.getFailures().size());
        return result;
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
                                  Map<String, List<CategoryDict>> dictCache) { }
    private record RowOutcome(String rspuId, String variantId) { }

    private PreparedImport prepare(ProductImportRequest request) {
        if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
            throw new BusinessException("没有可导入的商品行");
        }
        if (request.getRows().size() > MAX_ROWS) throw new BusinessException("单次导入不能超过 500 行");
        ProductImportPreview preview = new ProductImportPreview();
        preview.setColumns(ImportRowCodec.columns());
        Map<String, List<CategoryDict>> dicts = preloadDicts();
        Map<Integer, ProductImportRow> typed = new LinkedHashMap<>();
        Map<String, List<ProductImportPreviewRow>> groups = new LinkedHashMap<>();
        Map<String, RspuMaster> existingGroups = new HashMap<>();
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
            RspuMaster existing = null;
            try { existing = resolveExistingRspu(row.getRspuId(), row.getExternalCode()); }
            catch (BusinessException e) { source.getErrors().add(e.getMessage()); }
            String key = existing != null ? "id:" + existing.getRspuId()
                : StringUtils.hasText(row.getExternalCode()) ? "code:" + row.getExternalCode()
                : "row:" + source.getRowIndex();
            source.setGroupKey(key);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(source);
            if (existing != null) existingGroups.put(key, existing);
        }
        for (var entry : groups.entrySet()) {
            List<ProductImportPreviewRow> members = entry.getValue();
            RspuMaster existing = existingGroups.get(entry.getKey());
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
                if (!StringUtils.hasText(row.getProductName()) && existing != null) row.setProductName(existing.getProductName());
                normalizeRow(row, dicts);
                String error = validateRow(row, dicts);
                if (error != null) member.getErrors().add(error);
                if (member.getSourceRowIndex() > 0) {
                    int[] exact = SupplierProductAdapter.exactDimensions(row.getSizeText());
                    if (exact != null && ((row.getWidthMm() != null && !row.getWidthMm().equals(exact[0]))
                        || (row.getDepthMm() != null && !row.getDepthMm().equals(exact[1]))
                        || (row.getHeightMm() != null && !row.getHeightMm().equals(exact[2]))))
                        member.getErrors().add("结构化尺寸与三段尺寸原文不一致，请核对毫米值");
                }
                if (member.getErrors().isEmpty() && (existing == null || request.isUpdateIfExists())) {
                    String signature = variantSignature(row, dicts);
                    if (!variants.add(signature)) member.getErrors().add("同一商品存在重复规格（尺寸、颜色、材质），请合并或跳过重复行");
                }
                ImportRowCodec.encode(row, member);
                addReviewWarnings(member, row);
                typed.put(member.getRowIndex(), row);
                member.setAction(existing != null ? (request.isUpdateIfExists() ? "更新已有商品" : "跳过已有商品")
                    : members.indexOf(member) == 0 ? "新增商品" : "新增规格");
                if (!member.getErrors().isEmpty()) preview.setInvalidRows(preview.getInvalidRows() + 1);
            }
            if (members.stream().anyMatch(r -> r.getErrors().isEmpty())
                && (existing == null || request.isUpdateIfExists())) preview.setProductCount(preview.getProductCount() + 1);
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

    private String variantSignature(ProductImportRow row, Map<String, List<CategoryDict>> dicts) {
        String size = effectiveOf(resolveCodeOrNull("size", row.getSizeCode(), dicts.get("size")), row.getSizeText());
        return writeJsonListOrNull(List.of(
            size.isEmpty() ? rowDimensionKey(row) : size,
            effectiveOf(resolveCodeOrNull("color", row.getColorCode(), dicts.get("color")), row.getColorText()),
            effectiveOf(resolveCodeOrNull("material", row.getMaterialCode(), dicts.get("material")), row.getMaterialText())));
    }

    private String rowDimensionKey(ProductImportRow row) {
        return String.valueOf(row.getWidthMm()) + "/" + row.getDepthMm() + "/" + row.getHeightMm();
    }

    private String variantDimensionKey(RspuVariant variant) {
        if (!StringUtils.hasText(variant.getDimensions())) return "null/null/null";
        try {
            Map<?, ?> dimensions = objectMapper.readValue(variant.getDimensions(), Map.class);
            return dimensions.get("widthMm") + "/" + dimensions.get("depthMm") + "/" + dimensions.get("heightMm");
        } catch (Exception e) {
            return "null/null/null";
        }
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

    private Map<String, List<CategoryDict>> preloadDicts() {
        Map<String, List<CategoryDict>> cache = new HashMap<>();
        cache.put("material", safeList(dictService.listByType("material")));
        cache.put("size", safeList(dictService.listByType("size")));
        cache.put("color", safeList(dictService.listByType("color")));
        return cache;
    }

    private List<CategoryDict> safeList(List<CategoryDict> list) {
        return list == null ? List.of() : list;
    }

    private RspuMaster resolveExistingRspu(String rspuId, String externalCode) {
        RspuMaster byId = StringUtils.hasText(rspuId) ? rspuMasterMapper.selectById(rspuId.trim()) : null;
        if (StringUtils.hasText(rspuId) && byId == null) throw new BusinessException("RSPU ID 不存在: " + rspuId);
        List<RspuMaster> byCode = StringUtils.hasText(externalCode) ? rspuMasterMapper.selectList(
            new QueryWrapper<RspuMaster>().eq("external_code", externalCode.trim())) : List.of();
        if (byCode.size() > 1) throw new BusinessException("外部编码对应多个商品，请先整理重复商品或移除外部编码并填写 RSPU ID");
        RspuMaster match = byCode.isEmpty() ? null : byCode.getFirst();
        if (byId != null && match != null && !byId.getRspuId().equals(match.getRspuId())) {
            throw new BusinessException("RSPU ID 与外部编码指向不同商品");
        }
        if (byId != null && StringUtils.hasText(externalCode) && StringUtils.hasText(byId.getExternalCode())
            && !externalCode.trim().equals(byId.getExternalCode())) throw new BusinessException("外部编码与已有商品不一致");
        return byId != null ? byId : match;
    }

    private RowOutcome processRowInTransaction(ProductImportRow row, RspuMaster existing,
                                               Map<String, List<CategoryDict>> dictCache, boolean groupCommitted) {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        TransactionStatus status = transactionManager.getTransaction(def);
        try {
            RspuMaster rspu = existing;
            if (!groupCommitted) {
                rspu = existing != null ? updateRspu(existing, row) : createRspu(row);
            }
            RspuVariant variant = shouldCreateVariant(row) || hasFactoryPrice(row)
                ? createVariantIfNotExists(rspu.getRspuId(), row, dictCache) : null;
            saveImages(rspu.getRspuId(), row);
            saveFactoryPrice(rspu.getRspuId(), variant != null ? variant.getVariantId() : null, row);
            transactionManager.commit(status);
            return new RowOutcome(rspu.getRspuId(), variant != null ? variant.getVariantId() : null);
        } catch (Exception e) {
            if (!status.isCompleted()) transactionManager.rollback(status);
            if (e instanceof DataIntegrityViolationException constraint) {
                throw new BusinessException(ConstraintViolations.toUserMessage(constraint, "产品编码或规格冲突"));
            }
            throw e;
        }
    }

    private RspuMaster createRspu(ProductImportRow row) {
        String rspuId = IdGenerator.rspuId();
        RspuMaster rspu = buildRspuFromRow(new RspuMaster(), row, false);
        rspu.setRspuId(rspuId);
        rspu.setExternalCode(trim(row.getExternalCode()));
        rspu.setStatus("active");
        rspu.setAuditStatus("pending");
        rspu.setCreatedBy(CurrentUserContext.currentUsername());
        rspu.setCreatedAt(LocalDateTime.now());
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMasterMapper.insert(rspu);

        rspuCodeService.assignCode(rspuId);

        return rspu;
    }

    private RspuMaster updateRspu(RspuMaster rspu, ProductImportRow row) {
        buildRspuFromRow(rspu, row, true);
        if (!StringUtils.hasText(rspu.getExternalCode()) && StringUtils.hasText(row.getExternalCode())) rspu.setExternalCode(row.getExternalCode());
        rspu.setAuditStatus("pending");
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMasterMapper.updateById(rspu);
        return rspu;
    }

    private RspuMaster buildRspuFromRow(RspuMaster rspu, ProductImportRow row, boolean isUpdate) {
        if (!isUpdate || StringUtils.hasText(row.getProductName())) {
            rspu.setProductName(trim(row.getProductName()));
        }
        if (!isUpdate || StringUtils.hasText(row.getColorPrimaryName())) {
            rspu.setColorPrimaryName(trim(row.getColorPrimaryName()));
        }
        if (!isUpdate || StringUtils.hasText(row.getDescription())) {
            rspu.setDescription(trim(row.getDescription()));
        }
        if (!isUpdate || row.getRetailPrice() != null) {
            rspu.setRetailPrice(row.getRetailPrice());
        }
        if (!isUpdate || StringUtils.hasText(row.getPrimaryImageUrl())) {
            rspu.setPrimaryImageUrl(trim(row.getPrimaryImageUrl()));
        }
        if (!isUpdate || StringUtils.hasText(row.getDetailImageUrls())) {
            rspu.setDetailImageUrls(writeJsonListOrNull(splitCsv(row.getDetailImageUrls())));
        }
        return rspu;
    }

    private boolean shouldCreateVariant(ProductImportRow row) {
        return StringUtils.hasText(row.getVariantDisplayName())
            || StringUtils.hasText(row.getSizeCode())
            || StringUtils.hasText(row.getSizeText())
            || StringUtils.hasText(row.getColorCode())
            || StringUtils.hasText(row.getColorText())
            || StringUtils.hasText(row.getMaterialCode())
            || StringUtils.hasText(row.getMaterialText())
            || row.getWidthMm() != null || row.getDepthMm() != null || row.getHeightMm() != null
            || row.getPackageCount() != null || row.getPackageVolumeCbm() != null || row.getGrossWeight() != null;
    }

    private RspuVariant createVariantIfNotExists(String rspuId, ProductImportRow row,
                                          Map<String, List<CategoryDict>> dictCache) {
        String sizeCode = resolveCodeOrNull("size", row.getSizeCode(), dictCache.get("size"));
        String colorCode = resolveCodeOrNull("color", row.getColorCode(), dictCache.get("color"));
        String materialCode = resolveCodeOrNull("material", row.getMaterialCode(), dictCache.get("material"));

        String effSize = effectiveOf(sizeCode, row.getSizeText());
        String effColor = effectiveOf(colorCode, row.getColorText());
        String effMaterial = effectiveOf(materialCode, row.getMaterialText());

        List<RspuVariant> existing = rspuVariantMapper.selectList(
            new QueryWrapper<RspuVariant>().eq("rspu_id", rspuId));
        RspuVariant duplicate = existing.stream().filter(v ->
            effSize.equals(effectiveOf(v.getSizeCode(), v.getSizeText()))
                && (!effSize.isEmpty() || rowDimensionKey(row).equals(variantDimensionKey(v)))
                && effColor.equals(effectiveOf(v.getColorCode(), v.getColorText()))
                && effMaterial.equals(effectiveOf(v.getMaterialCode(), v.getMaterialText())))
            .findFirst().orElse(null);
        if (duplicate != null) {
            if (StringUtils.hasText(row.getVariantDisplayName())) duplicate.setDisplayName(row.getVariantDisplayName());
            String dimensions = dimensionsJson(row, duplicate.getDimensions());
            if (dimensions != null) duplicate.setDimensions(dimensions);
            if (row.getPackageCount() != null) duplicate.setPackageCount(row.getPackageCount());
            if (row.getPackageVolumeCbm() != null) duplicate.setPackageVolumeCbm(row.getPackageVolumeCbm());
            if (row.getGrossWeight() != null) duplicate.setGrossWeight(row.getGrossWeight());
            duplicate.setUpdatedAt(LocalDateTime.now());
            rspuVariantMapper.updateById(duplicate);
            return duplicate;
        }

        String variantId = "VAR-" + UUID.randomUUID().toString().toUpperCase(Locale.ROOT);

        RspuVariant variant = new RspuVariant();
        variant.setVariantId(variantId);
        variant.setRspuId(rspuId);
        variant.setDisplayName(StringUtils.hasText(row.getVariantDisplayName())
            ? row.getVariantDisplayName().trim()
            : "默认变体");
        variant.setSizeCode(sizeCode);
        variant.setSizeText(row.getSizeText());
        variant.setDimensions(dimensionsJson(row, null));
        variant.setPackageCount(row.getPackageCount());
        variant.setPackageVolumeCbm(row.getPackageVolumeCbm());
        variant.setGrossWeight(row.getGrossWeight());
        variant.setColorCode(colorCode);
        variant.setColorText(row.getColorText());
        variant.setMaterialCode(materialCode);
        variant.setMaterialText(row.getMaterialText());
        variant.setStatus("active");
        variant.setCreatedAt(LocalDateTime.now());
        variant.setUpdatedAt(LocalDateTime.now());
        rspuVariantMapper.insert(variant);
        return variant;
    }

    private String dimensionsJson(ProductImportRow row, String existingJson) {
        if (row.getWidthMm() == null && row.getDepthMm() == null && row.getHeightMm() == null) return null;
        Map<String, Object> dimensions = new LinkedHashMap<>();
        if (StringUtils.hasText(existingJson)) {
            try { dimensions.putAll(objectMapper.readValue(existingJson, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})); }
            catch (Exception ignored) { /* Invalid old JSON is replaced by confirmed values. */ }
        }
        if (row.getWidthMm() != null) dimensions.put("widthMm", row.getWidthMm());
        if (row.getDepthMm() != null) dimensions.put("depthMm", row.getDepthMm());
        if (row.getHeightMm() != null) dimensions.put("heightMm", row.getHeightMm());
        if (StringUtils.hasText(row.getSourceSizeText())) dimensions.put("sourceSizeText", row.getSourceSizeText());
        dimensions.put("unit", "mm");
        dimensions.put("source", "import_confirmed");
        try { return objectMapper.writeValueAsString(dimensions); }
        catch (Exception e) { throw new IllegalStateException("无法保存规格尺寸", e); }
    }

    private String resolveCodeOrNull(String dictType, String raw, List<CategoryDict> dicts) {
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

    private void saveImages(String rspuId, ProductImportRow row) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (StringUtils.hasText(row.getPrimaryImageUrl())) urls.add(row.getPrimaryImageUrl().trim());
        urls.addAll(splitCsv(row.getDetailImageUrls()));
        // A product without images has nothing to synchronize. Otherwise the final
        // updateById contains only the ID and MyBatis-Plus emits an empty SET clause.
        if (urls.isEmpty()) return;
        List<ImageAssets> existing = imageAssetsMapper.selectList(new QueryWrapper<ImageAssets>().eq("rspu_id", rspuId));
        Map<String, ImageAssets> byUrl = existing.stream().collect(Collectors.toMap(ImageAssets::getStorageUrl,
            a -> a, (a, b) -> a));
        // 显式主图替换时同步资产的主图标记；重复 URL 复用现有资产。
        boolean hasPrimary = existing.stream().anyMatch(a -> Boolean.TRUE.equals(a.getPrimary()));
        if (StringUtils.hasText(row.getPrimaryImageUrl())) {
            String primary = row.getPrimaryImageUrl().trim();
            for (ImageAssets image : existing) {
                boolean isPrimary = primary.equals(image.getStorageUrl());
                if (!Objects.equals(image.getPrimary(), isPrimary)) {
                    image.setPrimary(isPrimary);
                    image.setImageType(isPrimary ? "primary" : "detail");
                    imageAssetsMapper.updateById(image);
                }
            }
            hasPrimary = byUrl.containsKey(primary);
        }
        for (String url : urls) {
            if (byUrl.containsKey(url)) continue;
            ImageAssets image = new ImageAssets();
            image.setImageId(IdGenerator.imageId());
            image.setRspuId(rspuId);
            boolean primary = !hasPrimary;
            image.setImageType(primary ? "primary" : "detail");
            image.setStorageUrl(url);
            image.setPrimary(primary);
            image.setUploadedBy(CurrentUserContext.currentUsername());
            image.setCreatedAt(LocalDateTime.now());
            imageAssetsMapper.insert(image);
            hasPrimary |= primary;
        }

        // 同步主档图片字段，方便列表缩略图与旧接口读取
        List<ImageAssets> allImages = imageAssetsMapper.selectList(
            new QueryWrapper<ImageAssets>().eq("rspu_id", rspuId).orderByAsc("created_at"));
        String primaryUrl = allImages.stream().filter(a -> Boolean.TRUE.equals(a.getPrimary()))
            .map(ImageAssets::getStorageUrl).findFirst().orElse(null);
        if (primaryUrl == null && !allImages.isEmpty()) {
            primaryUrl = allImages.get(0).getStorageUrl();
        }
        final String finalPrimaryUrl = primaryUrl;
        List<String> detailUrls = allImages.stream()
            .filter(a -> !a.getStorageUrl().equals(finalPrimaryUrl))
            .map(ImageAssets::getStorageUrl).toList();
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setPrimaryImageUrl(primaryUrl);
        rspu.setDetailImageUrls(writeJsonListOrNull(detailUrls));
        rspuMasterMapper.updateById(rspu);
    }

    private boolean hasFactoryPrice(ProductImportRow row) {
        return row.getFactoryPrice() != null || row.getMoq() != null || row.getLeadTimeDays() != null;
    }

    private void saveFactoryPrice(String rspuId, String variantId, ProductImportRow row) {
        if (!hasFactoryPrice(row)) return;
        if (factoryProperties.getFactoryId() == null) throw new BusinessException("默认工厂尚未初始化");
        if (variantId == null) throw new BusinessException("报价必须关联商品规格");
        FactoryProductPrice price = new FactoryProductPrice();
        price.setRspuId(rspuId);
        price.setVariantId(variantId);
        price.setFactoryId(factoryProperties.getFactoryId());
        price.setFactoryPrice(row.getFactoryPrice());
        price.setMoq(row.getMoq());
        price.setLeadTimeDays(row.getLeadTimeDays());
        price.setEffectiveDate(LocalDate.now());
        factoryProductPriceMapper.upsert(price);
    }

    private String validateRow(ProductImportRow row, Map<String, List<CategoryDict>> dictCache) {
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
        if (StringUtils.hasText(row.getRspuId()) && row.getRspuId().trim().length() > 64) {
            return "RSPU ID 长度不能超过 64";
        }
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
            return value.length() <= 500 && ("http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme())) && uri.getHost() != null;
        } catch (IllegalArgumentException e) { return false; }
    }

    // ==================== 手工录入与商品维护 ====================

    @Transactional
    public Map<String, Object> createManualEntry(ManualProductEntryRequest request) {
        ProductImportRow row = toImportRow(request);
        Map<String, List<CategoryDict>> dictCache = preloadDicts();
        normalizeRow(row, dictCache);
        String error = validateRow(row, dictCache);
        if (error != null) throw new BusinessException(error);

        RspuMaster rspu = new RspuMaster();
        buildRspuFromRow(rspu, row, false);
        rspu.setRspuId(IdGenerator.rspuId());
        rspu.setExternalCode(trim(row.getExternalCode()));
        rspu.setStatus("active");
        rspu.setAuditStatus("pending");
        rspu.setCreatedBy(CurrentUserContext.currentUsername());
        rspu.setCreatedAt(LocalDateTime.now());
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMasterMapper.insert(rspu);

        rspuCodeService.assignCode(rspu.getRspuId());

        RspuVariant variant = createVariantIfNotExists(rspu.getRspuId(), row, dictCache);
        saveImages(rspu.getRspuId(), row);
        saveFactoryPrice(rspu.getRspuId(), variant.getVariantId(), row);

        return Map.of(
            "rspuId", rspu.getRspuId(),
            "variantId", variant.getVariantId(),
            "message", "手工录入产品成功"
        );
    }

    @Transactional
    public Map<String, Object> updateProduct(String rspuId, ManualProductEntryRequest request) {
        RspuMaster existing = rspuMasterMapper.selectById(rspuId);
        if (existing == null) throw new BusinessException("商品不存在: " + rspuId);

        ProductImportRow row = toImportRow(request);
        Map<String, List<CategoryDict>> dictCache = preloadDicts();
        normalizeRow(row, dictCache);
        String error = validateRow(row, dictCache);
        if (error != null) throw new BusinessException(error);

        buildRspuFromRow(existing, row, true);
        if (StringUtils.hasText(row.getExternalCode())) existing.setExternalCode(row.getExternalCode());
        if (StringUtils.hasText(request.getDescription())) existing.setDescription(request.getDescription().trim());
        existing.setUpdatedAt(LocalDateTime.now());
        rspuMasterMapper.updateById(existing);

        List<RspuVariant> variants = rspuVariantMapper.selectList(
            new QueryWrapper<RspuVariant>().eq("rspu_id", rspuId).orderByAsc("created_at"));
        RspuVariant variant = variants.isEmpty() ? null : variants.get(0);
        if (variant == null) {
            variant = createVariantIfNotExists(rspuId, row, dictCache);
        } else {
            if (StringUtils.hasText(row.getVariantDisplayName())) variant.setDisplayName(row.getVariantDisplayName());
            if (StringUtils.hasText(row.getSizeCode())) variant.setSizeCode(row.getSizeCode());
            if (StringUtils.hasText(row.getSizeText())) variant.setSizeText(row.getSizeText());
            String dimensions = dimensionsJson(row, variant.getDimensions());
            if (dimensions != null) variant.setDimensions(dimensions);
            if (StringUtils.hasText(row.getColorCode())) variant.setColorCode(row.getColorCode());
            if (StringUtils.hasText(row.getColorText())) variant.setColorText(row.getColorText());
            if (StringUtils.hasText(row.getMaterialCode())) variant.setMaterialCode(row.getMaterialCode());
            if (StringUtils.hasText(row.getMaterialText())) variant.setMaterialText(row.getMaterialText());
            variant.setUpdatedAt(LocalDateTime.now());
            rspuVariantMapper.updateById(variant);
        }

        saveImages(rspuId, row);
        saveFactoryPrice(rspuId, variant.getVariantId(), row);

        return Map.of("rspuId", rspuId, "message", "商品更新成功");
    }

    @Transactional
    public void deleteProduct(String rspuId) {
        deleteProducts(List.of(rspuId));
    }

    /**
     * Permanently removes products. This intentionally bypasses MyBatis-Plus
     * logical deletion so that the product and its owned rows are gone from
     * the database after the transaction commits.
     */
    @Transactional
    public int deleteProducts(Collection<String> rspuIds) {
        List<String> ids = rspuIds == null ? List.of() : rspuIds.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(id -> !id.isEmpty())
            .distinct()
            .toList();
        if (ids.isEmpty()) throw new BusinessException("请选择要删除的商品");
        int deleted = rspuMasterMapper.hardDeleteByRspuIds(ids);
        if (deleted == 0) throw new BusinessException("商品不存在: " + String.join(", ", ids));
        return deleted;
    }

    @Transactional
    public void restoreProduct(String rspuId) {
        int rows = rspuMasterMapper.restoreById(rspuId);
        if (rows == 0) throw new BusinessException("商品不存在或未被删除: " + rspuId);
    }

    public ProductDetailDto getProductDetail(String rspuId) {
        RspuMaster rspu = rspuMasterMapper.selectById(rspuId);
        if (rspu == null) throw new BusinessException("商品不存在: " + rspuId);
        return toDetailDto(rspu);
    }

    private ProductDetailDto toDetailDto(RspuMaster rspu) {
        ProductDetailDto dto = new ProductDetailDto();
        org.springframework.beans.BeanUtils.copyProperties(rspu, dto);

        List<RspuVariant> variants = rspuVariantMapper.selectList(
            new QueryWrapper<RspuVariant>().eq("rspu_id", rspu.getRspuId()).orderByAsc("created_at"));
        List<FactoryProductPrice> prices = factoryProductPriceMapper.selectList(
            new QueryWrapper<FactoryProductPrice>().eq("rspu_id", rspu.getRspuId()));
        Map<String, FactoryProductPrice> priceByVariant = prices.stream()
            .collect(Collectors.toMap(FactoryProductPrice::getVariantId, p -> p, (a, b) -> a));

        for (RspuVariant v : variants) {
            ProductDetailDto.Variant vd = new ProductDetailDto.Variant();
            org.springframework.beans.BeanUtils.copyProperties(v, vd);
            FactoryProductPrice price = priceByVariant.get(v.getVariantId());
            if (price != null) {
                vd.setFactoryPrice(price.getFactoryPrice());
                vd.setMoq(price.getMoq());
                vd.setLeadTimeDays(price.getLeadTimeDays());
            }
            dto.getVariants().add(vd);
        }

        List<ImageAssets> images = imageAssetsMapper.selectList(
            new QueryWrapper<ImageAssets>().eq("rspu_id", rspu.getRspuId()).orderByDesc("is_primary").orderByAsc("created_at"));
        for (ImageAssets img : images) {
            ProductDetailDto.Image id = new ProductDetailDto.Image();
            org.springframework.beans.BeanUtils.copyProperties(img, id);
            dto.getImages().add(id);
        }
        return dto;
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

    private void normalizeRow(ProductImportRow row, Map<String, List<CategoryDict>> dictCache) {
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

    private List<String> splitCsv(String value) {
        List<String> result = new ArrayList<>();
        if (!StringUtils.hasText(value)) {
            return result;
        }
        for (String part : value.split("[,，;；]")) {
            if (StringUtils.hasText(part)) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private String writeJsonListOrNull(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            log.warn("JSON 序列化失败", e);
            return null;
        }
    }

    private String trim(String value) {
        return value != null ? value.trim() : null;
    }
}
