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
    private final RspuStyleMapper rspuStyleMapper;
    private final RspuSceneMapper rspuSceneMapper;
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

    private static final List<String> SHARED_FIELDS = List.of(
        "categoryCode", "productName", "positioningLabel", "colorPrimaryName", "materialTags", "sceneTags",
        "productLevel", "warrantyYears", "referencePriceBand", "sixDimTags", "keySpecs", "description", "retailPrice");

    /** 上传解析和预检，不写入商品。 */
    public ProductImportPreview previewProducts(MultipartFile file) {
        validateFile(file);
        ProductImportRequest request = new ProductImportRequest();
        request.setRows(ImportRowCodec.read(file));
        return prepare(request).preview();
    }

    /** 校验用户编辑后的数据；跳过行不参与分组、继承或校验。 */
    public ProductImportPreview validatePreview(ProductImportRequest request) {
        return prepare(request).preview();
    }

    /** 兼容原文件导入接口，与预览确认使用同一处理逻辑。 */
    public ProductImportResult importProducts(MultipartFile file, boolean updateIfExists) {
        validateFile(file);
        ProductImportRequest request = new ProductImportRequest();
        request.setRows(ImportRowCodec.read(file));
        request.setUpdateIfExists(updateIfExists);
        return importRows(request);
    }

    /** 同商品多行只建一次主档；每个规格行单独提交，失败不占用分组状态。 */
    public synchronized ProductImportResult importRows(ProductImportRequest request) {
        PreparedImport prepared = prepare(request);
        ProductImportResult result = new ProductImportResult();
        result.setTotalRows(prepared.preview().getRows().size());
        result.setCleansingReport(prepared.preview().getCleansingReport());
        Map<String, String> committedGroups = new HashMap<>();
        Set<String> committedVariants = new HashSet<>();
        for (ProductImportPreviewRow source : prepared.preview().getRows()) {
            ProductImportRow row = prepared.rows().get(source.getRowIndex());
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
                RowOutcome outcome = processRowInTransaction(row, existing, prepared.dictCache(), committedId != null);
                committedGroups.put(source.getGroupKey(), outcome.rspuId());
                if (outcome.variantId() != null) committedVariants.add(outcome.variantId());
                result.setSuccessCount(result.getSuccessCount() + 1);
            } catch (Exception e) {
                log.warn("导入行失败，rowIndex={}", source.getRowIndex(), e);
                result.getFailures().add(new ProductImportFailure(source.getRowIndex(), row.getExternalCode(),
                    row.getRspuId(), e instanceof BusinessException ? e.getMessage() : "系统异常，请稍后重试"));
            }
        }
        result.setProductCount(committedGroups.size());
        result.setVariantCount(committedVariants.size());
        result.setFailedCount(result.getFailures().size());
        return result;
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
            source.setFields(new LinkedHashMap<>(input.getFields()));
            source.setSkipped(input.isSkipped());
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
                for (String decodeError : decodeSource.getErrors()) {
                    if (!member.getErrors().contains(decodeError)) member.getErrors().add(decodeError);
                }
                if (!StringUtils.hasText(row.getCategoryCode())) row.setCategoryCode(existing != null
                    && StringUtils.hasText(existing.getCategoryCode()) ? existing.getCategoryCode() : "OF");
                if (!StringUtils.hasText(row.getProductName()) && existing != null) row.setProductName(existing.getProductName());
                normalizeRow(row, dicts);
                String error = validateRow(row, dicts);
                if (error != null) member.getErrors().add(error);
                if (member.getErrors().isEmpty() && (existing == null || request.isUpdateIfExists())) {
                    String signature = variantSignature(row, dicts);
                    if (!variants.add(signature)) member.getErrors().add("同一商品存在重复规格（尺寸、颜色、材质），请合并或跳过重复行");
                }
                ImportRowCodec.encode(row, member);
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

    private String variantSignature(ProductImportRow row, Map<String, List<CategoryDict>> dicts) {
        return writeJsonListOrNull(List.of(
            effectiveOf(resolveCodeOrNull("size", row.getSizeCode(), dicts.get("size")), row.getSizeText()),
            effectiveOf(resolveCodeOrNull("color", row.getColorCode(), dicts.get("color")), row.getColorText()),
            effectiveOf(resolveCodeOrNull("material", row.getMaterialCode(), dicts.get("material")), row.getMaterialText())));
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
        cache.put("category", safeList(dictService.listByType("category")));
        cache.put("style", safeList(dictService.listByType("style")));
        cache.put("scene", safeList(dictService.listByType("scene")));
        cache.put("material", safeList(dictService.listByType("material")));
        cache.put("size", safeList(dictService.listByType("size")));
        cache.put("color", safeList(dictService.listByType("color")));
        cache.put("factory_level", safeList(dictService.listByType("factory_level")));
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
                rspu = existing != null ? updateRspu(existing, row, dictCache) : createRspu(row, dictCache);
                saveStyles(rspu.getRspuId(), row.getPositioningLabel(), dictCache.get("style"), existing != null);
                saveScenes(rspu.getRspuId(), splitCsv(row.getSceneTags()), dictCache.get("scene"), existing != null);
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

    private RspuMaster createRspu(ProductImportRow row, Map<String, List<CategoryDict>> dictCache) {
        String rspuId = IdGenerator.rspuId();
        RspuMaster rspu = buildRspuFromRow(new RspuMaster(), row, dictCache, false);
        rspu.setRspuId(rspuId);
        rspu.setExternalCode(trim(row.getExternalCode()));
        rspu.setStatus("active");
        rspu.setAuditStatus("pending");
        rspu.setCreatedBy(CurrentUserContext.currentUsername());
        rspu.setCreatedAt(LocalDateTime.now());
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMasterMapper.insert(rspu);

        String sizeCode = resolveImportSizeCode(row.getSizeCode(), dictCache.get("size"));
        String positioningLabel = rspu.getPositioningLabel();
        if (DictNormalizes.isValid(positioningLabel, dictCache.get("style"))) {
            rspuCodeService.assignCode(rspuId, rspu.getCategoryCode(), positioningLabel, sizeCode);
        }

        return rspu;
    }

    private RspuMaster updateRspu(RspuMaster rspu, ProductImportRow row, Map<String, List<CategoryDict>> dictCache) {
        buildRspuFromRow(rspu, row, dictCache, true);
        if (!StringUtils.hasText(rspu.getExternalCode()) && StringUtils.hasText(row.getExternalCode())) rspu.setExternalCode(row.getExternalCode());
        rspu.setAuditStatus("pending");
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMasterMapper.updateById(rspu);
        return rspu;
    }

    private RspuMaster buildRspuFromRow(RspuMaster rspu, ProductImportRow row,
                                       Map<String, List<CategoryDict>> dictCache, boolean isUpdate) {
        if (StringUtils.hasText(row.getCategoryCode())) {
            rspu.setCategoryCode(row.getCategoryCode().trim().toUpperCase());
            rspu.setCategoryPath(CategoryPaths.resolve(rspu.getCategoryCode()));
        } else if (!isUpdate) {
            rspu.setCategoryCode(null);
            rspu.setCategoryPath(null);
        }
        if (!isUpdate || StringUtils.hasText(row.getPositioningLabel())) {
            rspu.setPositioningLabel(StringUtils.hasText(row.getPositioningLabel())
                ? DictNormalizes.normalize(row.getPositioningLabel(), dictCache.get("style"))
                : "待识别");
        }
        if (!isUpdate || StringUtils.hasText(row.getProductName())) {
            rspu.setProductName(trim(row.getProductName()));
        }
        if (!isUpdate || StringUtils.hasText(row.getColorPrimaryName())) {
            rspu.setColorPrimaryName(trim(row.getColorPrimaryName()));
        }
        if (!isUpdate || StringUtils.hasText(row.getMaterialTags())) {
            rspu.setMaterialTags(writeJsonListOrNull(splitCsv(row.getMaterialTags())));
        }
        if (!isUpdate || StringUtils.hasText(row.getSceneTags())) {
            rspu.setSceneTags(writeJsonListOrNull(splitCsv(row.getSceneTags())));
        }
        if (!isUpdate || StringUtils.hasText(row.getSixDimTags())) {
            rspu.setSixDimTags(trim(row.getSixDimTags()));
        }
        if (!isUpdate || StringUtils.hasText(row.getReferencePriceBand())) {
            rspu.setReferencePriceBand(StringUtils.hasText(row.getReferencePriceBand())
                ? row.getReferencePriceBand().trim().toLowerCase()
                : null);
        }
        if (!isUpdate || StringUtils.hasText(row.getProductLevel())) {
            rspu.setProductLevel(StringUtils.hasText(row.getProductLevel())
                ? DictNormalizes.normalize(row.getProductLevel(), dictCache.get("factory_level"))
                : null);
        }
        if (!isUpdate || row.getWarrantyYears() != null) {
            rspu.setWarrantyYears(row.getWarrantyYears());
        }
        if (!isUpdate || StringUtils.hasText(row.getKeySpecs())) {
            rspu.setKeySpecs(trim(row.getKeySpecs()));
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

    private String resolveImportSizeCode(String rawSizeCode, List<CategoryDict> sizeDict) {
        if (StringUtils.hasText(rawSizeCode)) {
            String normalized = DictNormalizes.normalize(rawSizeCode, sizeDict);
            if (sizeDict.stream().anyMatch(d -> normalized.equalsIgnoreCase(d.getDictCode()))) {
                return normalized;
            }
        }
        return "X";
    }

    private void saveStyles(String rspuId, String positioningLabel, List<CategoryDict> styles, boolean isUpdate) {
        if (isUpdate && !StringUtils.hasText(positioningLabel)) {
            return;
        }
        if (isUpdate) {
            rspuStyleMapper.delete(new QueryWrapper<RspuStyle>().eq("rspu_id", rspuId));
        }
        String code = DictNormalizes.normalizeOrNull(positioningLabel, styles);
        if (code != null) {
            RspuStyle style = new RspuStyle();
            style.setRspuId(rspuId);
            style.setDictType("style");
            style.setStyleCode(code);
            style.setPrimary(true);
            style.setCreatedAt(LocalDateTime.now());
            rspuStyleMapper.insert(style);
        }
    }

    private void saveScenes(String rspuId, List<String> sceneCodes, List<CategoryDict> sceneDict, boolean isUpdate) {
        if (isUpdate && (sceneCodes == null || sceneCodes.isEmpty())) {
            return;
        }
        if (isUpdate) {
            rspuSceneMapper.delete(new QueryWrapper<RspuScene>().eq("rspu_id", rspuId));
        }
        if (sceneCodes == null || sceneCodes.isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (String raw : sceneCodes) {
            String code = resolveSceneCode(raw, sceneDict);
            if (code == null || !seen.add(code)) {
                continue;
            }
            RspuScene scene = new RspuScene();
            scene.setRspuId(rspuId);
            scene.setDictType("scene");
            scene.setSceneCode(code);
            scene.setCreatedAt(LocalDateTime.now());
            rspuSceneMapper.insert(scene);
        }
    }

    private String resolveSceneCode(String raw, List<CategoryDict> sceneDict) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        String byAlias = dictAliasService.resolveAlias("scene", trimmed);
        if (StringUtils.hasText(byAlias)) {
            return byAlias;
        }
        String normalized = DictNormalizes.normalize(trimmed, sceneDict);
        return DictNormalizes.isValid(normalized, sceneDict) ? normalized : null;
    }

    private boolean shouldCreateVariant(ProductImportRow row) {
        return StringUtils.hasText(row.getVariantDisplayName())
            || StringUtils.hasText(row.getSizeCode())
            || StringUtils.hasText(row.getSizeText())
            || StringUtils.hasText(row.getColorCode())
            || StringUtils.hasText(row.getColorText())
            || StringUtils.hasText(row.getMaterialCode())
            || StringUtils.hasText(row.getMaterialText());
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
                && effColor.equals(effectiveOf(v.getColorCode(), v.getColorText()))
                && effMaterial.equals(effectiveOf(v.getMaterialCode(), v.getMaterialText())))
            .findFirst().orElse(null);
        if (duplicate != null) {
            if (StringUtils.hasText(row.getVariantDisplayName())) duplicate.setDisplayName(row.getVariantDisplayName());
            if (StringUtils.hasText(row.getVariantReferencePriceBand())) duplicate.setReferencePriceBand(row.getVariantReferencePriceBand());
            if (StringUtils.hasText(row.getVariantProductLevel())) duplicate.setProductLevel(row.getVariantProductLevel());
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
        variant.setColorCode(colorCode);
        variant.setColorText(row.getColorText());
        variant.setMaterialCode(materialCode);
        variant.setMaterialText(row.getMaterialText());
        variant.setReferencePriceBand(StringUtils.hasText(row.getVariantReferencePriceBand())
            ? row.getVariantReferencePriceBand().trim().toLowerCase()
            : null);
        variant.setProductLevel(StringUtils.hasText(row.getVariantProductLevel())
            ? DictNormalizes.normalize(row.getVariantProductLevel(), dictCache.get("factory_level"))
            : null);
        variant.setStatus("active");
        variant.setCreatedAt(LocalDateTime.now());
        variant.setUpdatedAt(LocalDateTime.now());
        rspuVariantMapper.insert(variant);
        return variant;
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
        if (row.getQuantity() != null && row.getQuantity() < 1) return "数量必须大于等于 1";
        for (String url : splitCsv(row.getDetailImageUrls())) {
            if (!validImageUrl(url)) return "详情图 URL 必须是有效的 http/https 地址且不超过 500 字符";
        }
        if (StringUtils.hasText(row.getPrimaryImageUrl()) && !validImageUrl(row.getPrimaryImageUrl())) return "主图 URL 必须是有效的 http/https 地址且不超过 500 字符";
        if (StringUtils.hasText(row.getSizeText()) && row.getSizeText().length() > 100) return "尺寸原文不能超过 100 字符";
        if (StringUtils.hasText(row.getColorText()) && row.getColorText().length() > 100) return "颜色原文不能超过 100 字符";
        if (StringUtils.hasText(row.getMaterialText()) && row.getMaterialText().length() > 255) return "材质原文不能超过 255 字符";
        if (StringUtils.hasText(row.getVariantReferencePriceBand())
            && !List.of("low", "mid", "high").contains(row.getVariantReferencePriceBand().toLowerCase())) return "变体参考价格带必须是 low/mid/high 之一";
        if (StringUtils.hasText(row.getVariantProductLevel())
            && !DictNormalizes.isValid(row.getVariantProductLevel(), dictCache.get("factory_level"))) return "变体产品等级不存在";
        if (StringUtils.hasText(row.getCategoryCode())) {
            String categoryCode = row.getCategoryCode().trim().toUpperCase();
            if (categoryCode.length() > 16) {
                return "品类码 长度不能超过 16";
            }
            if (!DictNormalizes.isValid(categoryCode, dictCache.get("category"))) {
                return "品类码不存在: " + categoryCode;
            }
        }

        if (StringUtils.hasText(row.getRspuId()) && row.getRspuId().trim().length() > 64) {
            return "RSPU ID 长度不能超过 64";
        }
        if (StringUtils.hasText(row.getExternalCode()) && row.getExternalCode().trim().length() > 64) {
            return "外部编码 长度不能超过 64";
        }
        if (StringUtils.hasText(row.getPositioningLabel()) && row.getPositioningLabel().trim().length() > 64) {
            return "定位标签 长度不能超过 64";
        }
        if (StringUtils.hasText(row.getProductName()) && row.getProductName().trim().length() > 255) {
            return "商品名称 长度不能超过 255";
        }
        if (StringUtils.hasText(row.getVariantDisplayName()) && row.getVariantDisplayName().trim().length() > 128) {
            return "变体显示名称 长度不能超过 128";
        }

        if (StringUtils.hasText(row.getPositioningLabel())
            && !DictNormalizes.isValid(row.getPositioningLabel().trim(), dictCache.get("style"))) {
            return "定位标签不存在: " + row.getPositioningLabel();
        }
        if (StringUtils.hasText(row.getProductLevel())
            && !DictNormalizes.isValid(row.getProductLevel().trim(), dictCache.get("factory_level"))) {
            return "产品等级不存在: " + row.getProductLevel();
        }

        if (StringUtils.hasText(row.getReferencePriceBand())) {
            String band = row.getReferencePriceBand().trim().toLowerCase();
            if (!List.of("low", "mid", "high").contains(band)) {
                return "参考价格带必须是 low/mid/high 之一";
            }
        }
        if (row.getWarrantyYears() != null && (row.getWarrantyYears() < 0 || row.getWarrantyYears() > 100)) {
            return "保修年限 必须在 0~100 之间";
        }

        String sixDimError = validateJson(row.getSixDimTags(), "六维标签");
        if (sixDimError != null) {
            return sixDimError;
        }
        String keySpecsError = validateJson(row.getKeySpecs(), "关键规格");
        if (keySpecsError != null) {
            return keySpecsError;
        }

        return null;
    }

    private String validateJson(String json, String fieldName) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            var value = objectMapper.readTree(json.trim());
            if (value == null || !value.isObject()) return fieldName + "必须是 JSON 对象";
            return null;
        } catch (Exception e) {
            return fieldName + " 不是有效的 JSON 字符串";
        }
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
        buildRspuFromRow(rspu, row, dictCache, false);
        rspu.setRspuId(IdGenerator.rspuId());
        rspu.setExternalCode(trim(row.getExternalCode()));
        rspu.setStatus("active");
        rspu.setAuditStatus("pending");
        rspu.setCreatedBy(CurrentUserContext.currentUsername());
        rspu.setCreatedAt(LocalDateTime.now());
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMasterMapper.insert(rspu);

        if (DictNormalizes.isValid(rspu.getPositioningLabel(), dictCache.get("style"))) {
            rspuCodeService.assignCode(rspu.getRspuId(), rspu.getCategoryCode(), rspu.getPositioningLabel(),
                resolveImportSizeCode(row.getSizeCode(), dictCache.get("size")));
        }

        RspuVariant variant = createVariantIfNotExists(rspu.getRspuId(), row, dictCache);
        saveImages(rspu.getRspuId(), row);
        saveFactoryPrice(rspu.getRspuId(), variant.getVariantId(), row);
        saveStyles(rspu.getRspuId(), rspu.getPositioningLabel(), dictCache.get("style"), false);
        saveScenes(rspu.getRspuId(), splitCsv(row.getSceneTags()), dictCache.get("scene"), false);

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

        buildRspuFromRow(existing, row, dictCache, true);
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
            if (StringUtils.hasText(row.getColorCode())) variant.setColorCode(row.getColorCode());
            if (StringUtils.hasText(row.getColorText())) variant.setColorText(row.getColorText());
            if (StringUtils.hasText(row.getMaterialCode())) variant.setMaterialCode(row.getMaterialCode());
            if (StringUtils.hasText(row.getMaterialText())) variant.setMaterialText(row.getMaterialText());
            if (StringUtils.hasText(row.getVariantReferencePriceBand())) variant.setReferencePriceBand(row.getVariantReferencePriceBand());
            if (StringUtils.hasText(row.getVariantProductLevel())) variant.setProductLevel(row.getVariantProductLevel());
            variant.setUpdatedAt(LocalDateTime.now());
            rspuVariantMapper.updateById(variant);
        }

        saveImages(rspuId, row);
        saveFactoryPrice(rspuId, variant.getVariantId(), row);
        saveStyles(rspuId, existing.getPositioningLabel(), dictCache.get("style"), true);
        saveScenes(rspuId, splitCsv(row.getSceneTags()), dictCache.get("scene"), true);

        return Map.of("rspuId", rspuId, "message", "商品更新成功");
    }

    @Transactional
    public void deleteProduct(String rspuId) {
        RspuMaster rspu = rspuMasterMapper.selectById(rspuId);
        if (rspu == null) throw new BusinessException("商品不存在: " + rspuId);
        rspuMasterMapper.deleteById(rspuId);
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
        row.setCategoryCode(request.getCategoryCode());
        row.setPositioningLabel(request.getPositioningLabel());
        row.setProductName(request.getProductName());
        row.setColorPrimaryName(request.getColorPrimaryName());
        row.setMaterialTags(request.getMaterialTags() == null ? null : String.join(",", request.getMaterialTags()));
        row.setSceneTags(request.getSceneTags() == null ? null : String.join(",", request.getSceneTags()));
        row.setProductLevel(request.getProductLevel());
        row.setWarrantyYears(request.getWarrantyYears());
        row.setReferencePriceBand(request.getReferencePriceBand());
        row.setSixDimTags(request.getSixDimTags());
        row.setKeySpecs(request.getKeySpecs());
        row.setDescription(request.getDescription());
        row.setRetailPrice(request.getRetailPrice());
        row.setExternalCode(request.getExternalCode());
        row.setVariantDisplayName(request.getVariantDisplayName());
        row.setSizeCode(request.getSizeCode());
        row.setSizeText(request.getSizeText());
        row.setColorCode(request.getColorCode());
        row.setColorText(request.getColorText());
        row.setMaterialCode(request.getMaterialCode());
        row.setMaterialText(request.getMaterialText());
        row.setVariantReferencePriceBand(request.getVariantReferencePriceBand());
        row.setVariantProductLevel(request.getVariantProductLevel());
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
        row.setCategoryCode(trim(row.getCategoryCode()));
        row.setPositioningLabel(trim(row.getPositioningLabel()));
        row.setProductName(trim(row.getProductName()));
        row.setDescription(trim(row.getDescription()));
        row.setColorPrimaryName(trim(row.getColorPrimaryName()));
        row.setMaterialTags(trim(row.getMaterialTags()));
        row.setSceneTags(trim(row.getSceneTags()));
        row.setProductLevel(trim(row.getProductLevel()));
        row.setReferencePriceBand(trim(row.getReferencePriceBand()));
        row.setSixDimTags(trim(row.getSixDimTags()));
        row.setKeySpecs(trim(row.getKeySpecs()));
        row.setPrimaryImageUrl(trim(row.getPrimaryImageUrl()));
        row.setDetailImageUrls(trim(row.getDetailImageUrls()));
        row.setVariantDisplayName(trim(row.getVariantDisplayName()));
        row.setSizeCode(trim(row.getSizeCode()));
        row.setColorCode(trim(row.getColorCode()));
        row.setMaterialCode(trim(row.getMaterialCode()));
        row.setVariantReferencePriceBand(trim(row.getVariantReferencePriceBand()));
        row.setVariantProductLevel(trim(row.getVariantProductLevel()));

        if (StringUtils.hasText(row.getCategoryCode())) row.setCategoryCode(DictNormalizes.normalize(row.getCategoryCode(), dictCache.get("category")));
        if (StringUtils.hasText(row.getVariantProductLevel())) row.setVariantProductLevel(DictNormalizes.normalize(row.getVariantProductLevel(), dictCache.get("factory_level")));
        if (StringUtils.hasText(row.getSizeText())) row.setSizeText(row.getSizeText().trim());
        if (StringUtils.hasText(row.getColorText())) row.setColorText(row.getColorText().trim());
        if (StringUtils.hasText(row.getMaterialText())) row.setMaterialText(row.getMaterialText().trim());
        if (StringUtils.hasText(row.getSizeCode()) && resolveCodeOrNull("size", row.getSizeCode(), dictCache.get("size")) == null
            && !StringUtils.hasText(row.getSizeText())) row.setSizeText(row.getSizeCode());
        if (StringUtils.hasText(row.getColorCode()) && resolveCodeOrNull("color", row.getColorCode(), dictCache.get("color")) == null
            && !StringUtils.hasText(row.getColorText())) row.setColorText(row.getColorCode());
        if (StringUtils.hasText(row.getMaterialCode()) && resolveCodeOrNull("material", row.getMaterialCode(), dictCache.get("material")) == null
            && !StringUtils.hasText(row.getMaterialText())) row.setMaterialText(row.getMaterialCode());
        if (StringUtils.hasText(row.getPositioningLabel())) {
            row.setPositioningLabel(DictNormalizes.normalize(row.getPositioningLabel(), dictCache.get("style")));
        }
        if (StringUtils.hasText(row.getProductLevel())) {
            row.setProductLevel(DictNormalizes.normalize(row.getProductLevel(), dictCache.get("factory_level")));
        }
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
