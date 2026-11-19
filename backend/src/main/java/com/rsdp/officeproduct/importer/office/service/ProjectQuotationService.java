package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.response.ProductDetailDto;
import com.rsdp.officeproduct.importer.office.dto.ConfirmProjectQuotationRequest;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import com.rsdp.officeproduct.importer.office.dto.ProjectQuotationPreviewDto;
import com.rsdp.officeproduct.importer.office.dto.ProjectQuotationRecordDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationFeeDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationFloorDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationLineDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationRecordLineDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationSpaceDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationTotalsDto;
import com.rsdp.officeproduct.importer.office.entity.OfficeCanvas;
import com.rsdp.officeproduct.importer.office.entity.OfficeCart;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignFloor;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import com.rsdp.officeproduct.importer.office.entity.OfficeQuotation;
import com.rsdp.officeproduct.importer.office.entity.OfficeQuotationLine;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCanvasMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCartMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignFloorMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignProjectMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeQuotationLineMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeQuotationMapper;
import com.rsdp.officeproduct.importer.service.RsdpCatalogGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 项目报价试算（M1）：按 楼层 → 空间 → 商品 汇总画布与清单，套用两种报价口径。
 *
 * <p>只读预览与导出前试算不落库；确认后固化商品、费用与金额快照。
 * 底线拦截与上游变更失效联动留待后续版本。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectQuotationService {

    /** 系数口径上限，拦住误填导致的荒谬报价。 */
    private static final BigDecimal MAX_COEFFICIENT = new BigDecimal("100");
    /** 出厂价来自主项目详情，报价页反复试算时复用，避免每次逐商品拉取。 */
    private static final long FACTORY_PRICE_CACHE_TTL_MS = 120_000L;
    /** 单次确认的明细上限，防止超长请求。 */
    private static final int MAX_LINES = 2000;
    private static final int MAX_FEES = 50;
    private static final String SERVICE_FEE_NAME = "服务费";
    private static final String LOGISTICS_FEE_NAME = "物流搬运安装费";
    private static final String UNASSIGNED_ZONE_NAME = "未分区商品";

    private final OfficeProjectAccessService accessService;
    private final OfficeDesignProjectMapper projectMapper;
    private final OfficeDesignFloorMapper floorMapper;
    private final OfficeCanvasMapper canvasMapper;
    private final OfficeCartMapper cartMapper;
    private final OfficeQuotationMapper quotationMapper;
    private final OfficeQuotationLineMapper quotationLineMapper;
    private final RsdpCatalogGateway catalogGateway;
    private final OfficeAuditService auditService;
    private final ObjectMapper objectMapper;

    private final Map<String, FactoryPriceCache> factoryPriceCache = new ConcurrentHashMap<>();

    public ProjectQuotationPreviewDto preview(String projectId, String mode, BigDecimal value) {
        OfficeDesignProject project = requireProject(projectId);
        accessService.requireWorkspaceOwner(projectId);
        String pricingMode = normalizeMode(mode);
        BigDecimal pricingValue = normalizeValue(pricingMode, value);

        List<OfficeDesignFloor> floors = floorMapper.selectList(new QueryWrapper<OfficeDesignFloor>()
            .eq("project_id", projectId).orderByAsc("sort_order").orderByAsc("created_at"));
        Map<String, OfficeProductDto> productsById = loadProducts();

        List<FloorAggregate> aggregates = floors.stream().map(this::collectFloor).toList();
        Map<String, BigDecimal> factoryPrices = resolveFactoryPrices(aggregates, productsById);

        ProjectQuotationPreviewDto dto = new ProjectQuotationPreviewDto();
        dto.setProjectId(projectId);
        dto.setProjectName(project.getName());
        dto.setPricingMode(pricingMode);
        dto.setPricingValue(pricingValue);
        dto.setGeneratedAt(LocalDateTime.now());

        QuotationTotalsDto totals = new QuotationTotalsDto();
        int lineCount = 0;
        int missingLines = 0;
        for (FloorAggregate aggregate : aggregates) {
            QuotationFloorDto floorDto = new QuotationFloorDto();
            floorDto.setFloorId(aggregate.floor().getFloorId());
            floorDto.setFloorName(aggregate.floor().getName());
            for (ZoneAggregate zone : aggregate.zones()) {
                QuotationSpaceDto spaceDto = new QuotationSpaceDto();
                spaceDto.setZoneId(zone.zoneId());
                spaceDto.setZoneName(zone.zoneName());
                for (Map.Entry<String, Integer> entry : zone.productQty().entrySet()) {
                    QuotationLineDto line = buildLine(entry.getKey(), entry.getValue(), productsById, factoryPrices,
                        pricingMode, pricingValue);
                    spaceDto.getLines().add(line);
                    lineCount++;
                    if (line.getIssue() != null) {
                        missingLines++;
                    } else {
                        spaceDto.setFactoryTotal(spaceDto.getFactoryTotal().add(line.getFactoryPrice()
                            .multiply(BigDecimal.valueOf(entry.getValue()))));
                        spaceDto.setDealTotal(spaceDto.getDealTotal().add(line.getSubtotal()));
                    }
                }
                if (spaceDto.getLines().isEmpty()) {
                    continue;
                }
                floorDto.getSpaces().add(spaceDto);
                floorDto.setFactoryTotal(floorDto.getFactoryTotal().add(spaceDto.getFactoryTotal()));
                floorDto.setDealTotal(floorDto.getDealTotal().add(spaceDto.getDealTotal()));
            }
            if (floorDto.getSpaces().isEmpty()) {
                continue;
            }
            dto.getFloors().add(floorDto);
            totals.setFactoryTotal(totals.getFactoryTotal().add(floorDto.getFactoryTotal()));
            totals.setDealTotal(totals.getDealTotal().add(floorDto.getDealTotal()));
        }
        totals.setLineCount(lineCount);
        totals.setMissingFactoryPriceLines(missingLines);
        dto.setTotals(totals);
        return dto;
    }

    /** 清单行优先于画布家具：有清单的按清单数量，没有清单的空间按画布家具件数。 */
    private FloorAggregate collectFloor(OfficeDesignFloor floor) {
        List<OfficeCart> cart = cartMapper.selectList(new QueryWrapper<OfficeCart>()
            .eq("project_id", floor.getFloorId()).orderByAsc("created_at"));
        Map<String, String> zoneNames = new LinkedHashMap<>();
        Map<String, Integer> furnitureQty = new LinkedHashMap<>();
        OfficeCanvas canvas = canvasMapper.selectOne(new QueryWrapper<OfficeCanvas>()
            .eq("project_id", floor.getFloorId()).orderByDesc("created_at").orderByDesc("canvas_id").last("limit 1"));
        if (canvas != null) {
            JsonNode objects = readObjects(canvas.getObjectsJson());
            for (JsonNode node : objects) {
                if ("zone".equals(node.path("kind").asText())) {
                    zoneNames.put(node.path("id").asText(), node.path("name").asText(""));
                }
            }
            for (JsonNode node : objects) {
                if (!"furniture".equals(node.path("kind").asText())) {
                    continue;
                }
                String productId = node.path("productId").asText("");
                if (!StringUtils.hasText(productId)) {
                    continue;
                }
                String zoneId = node.path("zoneId").asText("");
                furnitureQty.merge(zoneId + "|" + productId, 1, Integer::sum);
            }
        }

        Map<String, ZoneAggregate> zones = new LinkedHashMap<>();
        Set<String> cartZones = new LinkedHashSet<>();
        for (OfficeCart item : cart) {
            if (!StringUtils.hasText(item.getProductId())) {
                continue;
            }
            String zoneId = item.getZoneId() == null ? "" : item.getZoneId();
            cartZones.add(zoneId);
            ZoneAggregate zone = zones.computeIfAbsent(zoneId, id -> new ZoneAggregate(id,
                StringUtils.hasText(item.getZoneName()) ? item.getZoneName()
                    : zoneNames.getOrDefault(id, UNASSIGNED_ZONE_NAME)));
            zone.productQty().merge(item.getProductId(), item.getQty() == null ? 1 : item.getQty(), Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : furnitureQty.entrySet()) {
            int split = entry.getKey().indexOf('|');
            String zoneId = split < 0 ? entry.getKey() : entry.getKey().substring(0, split);
            String productId = split < 0 ? "" : entry.getKey().substring(split + 1);
            if (cartZones.contains(zoneId) || !StringUtils.hasText(productId)) {
                continue;
            }
            ZoneAggregate zone = zones.computeIfAbsent(zoneId, id -> new ZoneAggregate(id,
                zoneNames.getOrDefault(id, UNASSIGNED_ZONE_NAME)));
            zone.productQty().merge(productId, entry.getValue(), Integer::sum);
        }
        return new FloorAggregate(floor, new ArrayList<>(zones.values()));
    }

    private QuotationLineDto buildLine(String productId, Integer qty, Map<String, OfficeProductDto> productsById,
                                       Map<String, BigDecimal> factoryPrices, String pricingMode, BigDecimal pricingValue) {
        OfficeProductDto product = productsById.get(productId);
        QuotationLineDto line = new QuotationLineDto();
        line.setProductId(productId);
        line.setQty(qty == null || qty < 1 ? 1 : qty);
        if (product == null) {
            line.setProductName("商品不可用");
            line.setIssue("商品已下架或不在当前商品目录，需重新选品");
            return line;
        }
        line.setRspuId(product.getRspuId());
        line.setProductName(product.getName());
        line.setImageUrl(product.getImageUrl());
        line.setVariant(product.getVariant());
        BigDecimal factoryPrice = factoryPrices.get(productId);
        if (factoryPrice == null) {
            line.setIssue("缺出厂价，无法试算");
            return line;
        }
        BigDecimal unitPrice = calcUnitPrice(factoryPrice, pricingMode, pricingValue);
        line.setFactoryPrice(factoryPrice);
        line.setUnitPrice(unitPrice);
        line.setSubtotal(unitPrice.multiply(BigDecimal.valueOf(line.getQty())).setScale(2, RoundingMode.HALF_UP));
        return line;
    }

    private BigDecimal calcUnitPrice(BigDecimal factoryPrice, String pricingMode, BigDecimal pricingValue) {
        if ("MARGIN".equals(pricingMode)) {
            return factoryPrice.divide(BigDecimal.ONE.subtract(pricingValue), 2, RoundingMode.HALF_UP);
        }
        return factoryPrice.multiply(pricingValue).setScale(2, RoundingMode.HALF_UP);
    }

    private Map<String, OfficeProductDto> loadProducts() {
        // 报价需解析清单里已选商品的价格，不受公共库开关变化影响，统一取全量目录。
        return catalogGateway.listOfficeProducts("full").stream()
            .filter(product -> StringUtils.hasText(product.getId()))
            .collect(Collectors.toMap(OfficeProductDto::getId, product -> product, (first, second) -> first,
                LinkedHashMap::new));
    }

    /** 出厂价按变体挂在主项目报价上：按 rspu 去重后逐个取详情，缺价行单独提示。 */
    private Map<String, BigDecimal> resolveFactoryPrices(List<FloorAggregate> aggregates,
                                                        Map<String, OfficeProductDto> productsById) {
        Set<String> productIds = new LinkedHashSet<>();
        for (FloorAggregate aggregate : aggregates) {
            for (ZoneAggregate zone : aggregate.zones()) {
                productIds.addAll(zone.productQty().keySet());
            }
        }
        Map<String, BigDecimal> result = new HashMap<>();
        Set<String> rspuIds = productIds.stream()
            .map(productsById::get)
            .filter(product -> product != null && StringUtils.hasText(product.getRspuId()))
            .map(OfficeProductDto::getRspuId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Map<String, BigDecimal>> pricesByRspu = new HashMap<>();
        for (String rspuId : rspuIds) {
            pricesByRspu.put(rspuId, cachedFactoryPrices(rspuId));
        }
        for (String productId : productIds) {
            OfficeProductDto product = productsById.get(productId);
            if (product == null || !StringUtils.hasText(product.getRspuId())) {
                continue;
            }
            BigDecimal price = pricesByRspu.getOrDefault(product.getRspuId(), Map.of()).get(productId);
            if (price != null) {
                result.put(productId, price);
            }
        }
        return result;
    }

    private Map<String, BigDecimal> cachedFactoryPrices(String rspuId) {
        FactoryPriceCache cached = factoryPriceCache.get(rspuId);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < FACTORY_PRICE_CACHE_TTL_MS) {
            return cached.prices();
        }
        Map<String, BigDecimal> prices = new HashMap<>();
        try {
            ProductDetailDto detail = catalogGateway.getProductDetail(rspuId);
            for (ProductDetailDto.Variant variant : detail.getVariants()) {
                if (StringUtils.hasText(variant.getVariantId()) && variant.getFactoryPrice() != null) {
                    prices.put(variant.getVariantId(), variant.getFactoryPrice());
                }
            }
        } catch (RuntimeException failure) {
            log.warn("读取商品出厂价失败 rspuId={}：{}", rspuId, failure.getMessage());
        }
        factoryPriceCache.put(rspuId, new FactoryPriceCache(prices, System.currentTimeMillis()));
        return prices;
    }

    private JsonNode readObjects(String objectsJson) {
        if (!StringUtils.hasText(objectsJson)) {
            return objectMapper.createArrayNode();
        }
        try {
            JsonNode node = objectMapper.readTree(objectsJson);
            return node != null && node.isArray() ? node : objectMapper.createArrayNode();
        } catch (Exception failure) {
            log.warn("画布对象解析失败：{}", failure.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    private String normalizeMode(String mode) {
        if (!StringUtils.hasText(mode) || "COEFFICIENT".equalsIgnoreCase(mode.trim())) {
            return "COEFFICIENT";
        }
        if ("MARGIN".equalsIgnoreCase(mode.trim())) {
            return "MARGIN";
        }
        throw new BusinessException("报价口径只能是 COEFFICIENT 或 MARGIN");
    }

    private BigDecimal normalizeValue(String pricingMode, BigDecimal value) {
        if ("MARGIN".equals(pricingMode)) {
            BigDecimal margin = value == null ? new BigDecimal("0.35") : value;
            if (margin.signum() <= 0 || margin.compareTo(BigDecimal.ONE) >= 0) {
                throw new BusinessException("毛利率须大于 0 且小于 1（如 0.35 表示 35%）");
            }
            return margin;
        }
        BigDecimal coefficient = value == null ? new BigDecimal("1.20") : value;
        if (coefficient.signum() <= 0 || coefficient.compareTo(MAX_COEFFICIENT) > 0) {
            throw new BusinessException("系数须大于 0 且不超过 100");
        }
        return coefficient;
    }

    /** 确认报价：使用与导出相同的服务端重算结果，固化快照并取代上一版。 */
    @Transactional
    public ProjectQuotationRecordDto confirm(String projectId, ConfirmProjectQuotationRequest request) {
        PreparedQuotation prepared = prepare(projectId, request);
        OfficeQuotation quotation = prepared.quotation();
        quotation.setFeesJson(writeFees(prepared.fees()));

        // 锁定项目后再分配版本，首次确认也能串行写入。
        if (projectMapper.selectOne(new QueryWrapper<OfficeDesignProject>().select("project_id")
                .eq("project_id", projectId).last("FOR UPDATE")) == null) {
            throw new BusinessException("项目不存在");
        }
        String quotationId = "QT-" + UUID.randomUUID().toString().replace("-", "");
        int revision = nextRevision(projectId);
        quotation.setQuotationId(quotationId);
        quotation.setRevision(revision);
        quotation.setStatus("CONFIRMED");
        quotation.setCreatedBy(accessService.currentUserId());
        quotation.setCreatedAt(LocalDateTime.now());

        OfficeQuotation superseded = new OfficeQuotation();
        superseded.setStatus("SUPERSEDED");
        superseded.setSupersededAt(LocalDateTime.now());
        quotationMapper.update(superseded, new QueryWrapper<OfficeQuotation>()
            .eq("project_id", projectId).eq("status", "CONFIRMED"));

        quotationMapper.insert(quotation);
        for (OfficeQuotationLine row : prepared.rows()) {
            row.setQuotationId(quotationId);
            quotationLineMapper.insert(row);
        }
        ProjectQuotationRecordDto record = toRecordDto(quotation, prepared.rows(), prepared.fees());
        auditService.record("QUOTATION_CONFIRMED", "PROJECT", projectId,
            "quotationId=" + quotationId + "; revision=" + revision + "; total=" + quotation.getTotalAmount()
                + "; lines=" + record.getLineCount() + "; missing=" + record.getMissingPriceLines());
        return record;
    }

    /** 导出前的试算：与确认共用校验和金额计算，但不分配版本、不写数据库或审计。 */
    public ProjectQuotationRecordDto previewConfirmation(String projectId, ConfirmProjectQuotationRequest request) {
        PreparedQuotation prepared = prepare(projectId, request);
        return toRecordDto(prepared.quotation(), prepared.rows(), prepared.fees());
    }

    private PreparedQuotation prepare(String projectId, ConfirmProjectQuotationRequest request) {
        OfficeDesignProject project = requireProject(projectId);
        accessService.requireWorkspaceOwner(projectId);
        if (request == null || request.getLines() == null || request.getLines().isEmpty()) {
            throw new BusinessException("没有可确认的报价明细");
        }
        if (request.getLines().size() > MAX_LINES) {
            throw new BusinessException("报价明细不能超过 " + MAX_LINES + " 行，请拆分后再确认");
        }
        String pricingMode = normalizeMode(request.getPricingMode());
        BigDecimal pricingValue = normalizeValue(pricingMode, request.getPricingValue());
        List<QuotationFeeDto> fees = normalizeFees(request.getFees());
        BigDecimal feeTotal = feeTotal(fees);

        Map<String, OfficeDesignFloor> floorsById = floorMapper.selectList(new QueryWrapper<OfficeDesignFloor>()
                .eq("project_id", projectId)).stream()
            .collect(Collectors.toMap(OfficeDesignFloor::getFloorId, floor -> floor, (first, second) -> first,
                LinkedHashMap::new));
        Map<String, OfficeProductDto> productsById = loadProducts();

        BigDecimal goodsTotal = BigDecimal.ZERO.setScale(2);
        List<OfficeQuotationLine> rows = new ArrayList<>();
        for (ConfirmProjectQuotationRequest.Line line : request.getLines()) {
            if (line == null || !StringUtils.hasText(line.getProductId())) {
                throw new BusinessException("报价明细缺少商品");
            }
            OfficeDesignFloor floor = StringUtils.hasText(line.getFloorId()) ? floorsById.get(line.getFloorId()) : null;
            if (floor == null) {
                throw new BusinessException("报价明细包含不属于该项目的楼层：" + line.getFloorId());
            }
            int qty = line.getQty() == null ? 1 : line.getQty();
            if (qty < 0) {
                throw new BusinessException("数量不能为负数");
            }

            OfficeQuotationLine row = new OfficeQuotationLine();
            row.setLineNo(rows.size());
            row.setFloorId(floor.getFloorId());
            row.setFloorName(floor.getName());
            row.setZoneId(line.getZoneId());
            row.setZoneName(StringUtils.hasText(line.getZoneName()) ? line.getZoneName() : UNASSIGNED_ZONE_NAME);
            row.setProductId(line.getProductId());
            row.setQty(qty);

            OfficeProductDto product = productsById.get(line.getProductId());
            BigDecimal factoryPrice = product == null ? null : factoryPriceOf(product);
            String issue = null;
            if (product == null) {
                issue = "商品已下架或不在当前商品目录，需重新选品";
                row.setProductName("商品不可用");
            } else {
                row.setRspuId(product.getRspuId());
                row.setProductName(product.getName());
                row.setVariant(product.getVariant());
                row.setImageUrl(product.getImageUrl());
                if (factoryPrice == null) {
                    issue = "缺出厂价，无法试算";
                }
            }

            BigDecimal unitPrice = null;
            if (issue == null) {
                unitPrice = line.getUnitPrice() != null
                    ? line.getUnitPrice() : calcUnitPrice(factoryPrice, pricingMode, pricingValue);
                if (unitPrice.signum() < 0) {
                    throw new BusinessException("成交单价不能为负数");
                }
                unitPrice = unitPrice.setScale(2, RoundingMode.HALF_UP);
                goodsTotal = goodsTotal.add(lineSubtotal(unitPrice, qty));
            }
            row.setFactoryPrice(factoryPrice);
            row.setUnitPrice(unitPrice);
            row.setIssue(issue);
            rows.add(row);
        }

        OfficeQuotation quotation = new OfficeQuotation();
        quotation.setProjectId(projectId);
        quotation.setProjectName(project.getName());
        quotation.setWorkspaceId(project.getWorkspaceId());
        quotation.setStatus("DRAFT");
        quotation.setPricingMode(pricingMode);
        quotation.setPricingValue(pricingValue);
        quotation.setTotalAmount(goodsTotal.add(feeTotal));
        return new PreparedQuotation(quotation, rows, fees);
    }

    /** 最近一次确认的报价单（含已失效版本），没有则返回 null。 */
    public ProjectQuotationRecordDto latest(String projectId) {
        requireProject(projectId);
        accessService.requireWorkspaceOwner(projectId);
        OfficeQuotation quotation = quotationMapper.selectOne(new QueryWrapper<OfficeQuotation>()
            .eq("project_id", projectId).orderByDesc("revision").last("limit 1"));
        if (quotation == null) {
            return null;
        }
        List<OfficeQuotationLine> rows = quotationLineMapper.findByQuotationId(quotation.getQuotationId());
        return toRecordDto(quotation, rows, readFees(quotation.getFeesJson()));
    }

    private int nextRevision(String projectId) {
        OfficeQuotation last = quotationMapper.selectOne(new QueryWrapper<OfficeQuotation>()
            .eq("project_id", projectId).orderByDesc("revision").last("limit 1"));
        return last == null || last.getRevision() == null ? 1 : last.getRevision() + 1;
    }

    private BigDecimal factoryPriceOf(OfficeProductDto product) {
        if (!StringUtils.hasText(product.getRspuId())) {
            return null;
        }
        return cachedFactoryPrices(product.getRspuId()).get(product.getId());
    }

    private BigDecimal feeOrZero(BigDecimal fee, String label) {
        if (fee == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        if (fee.signum() < 0) {
            throw new BusinessException(label + "不能为负数");
        }
        return fee.setScale(2, RoundingMode.HALF_UP);
    }

    private List<QuotationFeeDto> normalizeFees(List<ConfirmProjectQuotationRequest.Fee> requestFees) {
        if (requestFees == null || requestFees.isEmpty()) {
            return new ArrayList<>();
        }
        if (requestFees.size() > MAX_FEES) {
            throw new BusinessException("费用项不能超过 " + MAX_FEES + " 条");
        }
        List<QuotationFeeDto> fees = new ArrayList<>();
        for (ConfirmProjectQuotationRequest.Fee requestFee : requestFees) {
            if (requestFee == null) {
                throw new BusinessException("费用项不能为空");
            }
            String name = requestFee.getName() == null ? "" : requestFee.getName().trim();
            BigDecimal amount = feeOrZero(requestFee.getAmount(), "费用金额");
            if (!StringUtils.hasText(name)) {
                if (amount.signum() == 0) {
                    continue;
                }
                throw new BusinessException("请填写费用名称");
            }
            if (name.length() > 100) {
                throw new BusinessException("费用名称不能超过 100 字");
            }
            QuotationFeeDto fee = new QuotationFeeDto();
            fee.setName(name);
            fee.setAmount(amount);
            fees.add(fee);
        }
        return fees;
    }

    private BigDecimal feeTotal(List<QuotationFeeDto> fees) {
        BigDecimal total = BigDecimal.ZERO;
        for (QuotationFeeDto fee : fees) {
            total = total.add(fee.getAmount());
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal namedFeeTotal(List<QuotationFeeDto> fees, String name) {
        BigDecimal total = BigDecimal.ZERO;
        for (QuotationFeeDto fee : fees) {
            if (name.equals(fee.getName())) {
                total = total.add(fee.getAmount());
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private String writeFees(List<QuotationFeeDto> fees) {
        try {
            return objectMapper.writeValueAsString(fees);
        } catch (Exception failure) {
            throw new BusinessException("报价费用保存失败", failure);
        }
    }

    private List<QuotationFeeDto> readFees(String feesJson) {
        try {
            return objectMapper.readValue(feesJson, new TypeReference<List<QuotationFeeDto>>() {});
        } catch (Exception failure) {
            throw new BusinessException("报价费用读取失败", failure);
        }
    }

    private BigDecimal lineSubtotal(BigDecimal unitPrice, int qty) {
        return unitPrice.multiply(BigDecimal.valueOf(qty));
    }

    private ProjectQuotationRecordDto toRecordDto(OfficeQuotation quotation, List<OfficeQuotationLine> rows,
                                                  List<QuotationFeeDto> fees) {
        ProjectQuotationRecordDto dto = new ProjectQuotationRecordDto();
        dto.setQuotationId(quotation.getQuotationId());
        dto.setProjectId(quotation.getProjectId());
        dto.setProjectName(quotation.getProjectName());
        dto.setRevision(quotation.getRevision());
        dto.setStatus(quotation.getStatus());
        dto.setPricingMode(quotation.getPricingMode());
        dto.setPricingValue(quotation.getPricingValue());
        dto.setServiceFee(namedFeeTotal(fees, SERVICE_FEE_NAME));
        dto.setLogisticsFee(namedFeeTotal(fees, LOGISTICS_FEE_NAME));
        dto.setFees(fees);
        dto.setTotalAmount(quotation.getTotalAmount());
        dto.setLineCount(rows.size());
        dto.setCreatedBy(quotation.getCreatedBy());
        dto.setCreatedAt(quotation.getCreatedAt());
        BigDecimal goodsTotal = BigDecimal.ZERO.setScale(2);
        int missingLines = 0;
        for (OfficeQuotationLine row : rows) {
            QuotationRecordLineDto line = new QuotationRecordLineDto();
            line.setFloorId(row.getFloorId());
            line.setFloorName(row.getFloorName());
            line.setZoneId(row.getZoneId());
            line.setZoneName(row.getZoneName());
            line.setProductId(row.getProductId());
            line.setRspuId(row.getRspuId());
            line.setProductName(row.getProductName());
            line.setVariant(row.getVariant());
            line.setImageUrl(row.getImageUrl());
            line.setQty(row.getQty());
            line.setFactoryPrice(row.getFactoryPrice());
            line.setUnitPrice(row.getUnitPrice());
            if (row.getUnitPrice() != null) {
                BigDecimal subtotal = lineSubtotal(row.getUnitPrice(), row.getQty());
                line.setSubtotal(subtotal);
                goodsTotal = goodsTotal.add(subtotal);
            }
            if (row.getIssue() != null) {
                missingLines++;
            }
            line.setIssue(row.getIssue());
            dto.getLines().add(line);
        }
        dto.setGoodsTotal(goodsTotal);
        dto.setMissingPriceLines(missingLines);
        return dto;
    }

    private OfficeDesignProject requireProject(String projectId) {
        OfficeDesignProject project = StringUtils.hasText(projectId) ? projectMapper.selectById(projectId) : null;
        if (project == null) {
            throw new BusinessException("项目不存在");
        }
        return project;
    }

    private record FactoryPriceCache(Map<String, BigDecimal> prices, long fetchedAt) {
    }

    private record PreparedQuotation(OfficeQuotation quotation, List<OfficeQuotationLine> rows, List<QuotationFeeDto> fees) {
    }

    private record FloorAggregate(OfficeDesignFloor floor, List<ZoneAggregate> zones) {
    }

    private static final class ZoneAggregate {

        private final String zoneId;
        private final String zoneName;
        private final Map<String, Integer> productQty = new LinkedHashMap<>();

        private ZoneAggregate(String zoneId, String zoneName) {
            this.zoneId = zoneId;
            this.zoneName = zoneName;
        }

        private String zoneId() {
            return zoneId;
        }

        private String zoneName() {
            return zoneName;
        }

        private Map<String, Integer> productQty() {
            return productQty;
        }
    }
}
