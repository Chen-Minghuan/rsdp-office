package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import com.rsdp.officeproduct.importer.office.dto.ProjectQuotationPreviewDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationFloorDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationLineDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationSpaceDto;
import com.rsdp.officeproduct.importer.office.dto.QuotationTotalsDto;
import com.rsdp.officeproduct.importer.office.entity.OfficeCanvas;
import com.rsdp.officeproduct.importer.office.entity.OfficeCart;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignFloor;
import com.rsdp.officeproduct.importer.office.entity.OfficeDesignProject;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCanvasMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCartMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeDesignFloorMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 按楼层与空间汇总已选商品，生成只读报价试算。 */
@Slf4j
@Component
@RequiredArgsConstructor
class ProjectQuotationPreviewBuilder {

    private static final String UNASSIGNED_ZONE_NAME = "未分区商品";

    private final OfficeDesignFloorMapper floorMapper;
    private final OfficeCanvasMapper canvasMapper;
    private final OfficeCartMapper cartMapper;
    private final QuotationCatalog catalog;
    private final ObjectMapper objectMapper;

    ProjectQuotationPreviewDto build(OfficeDesignProject project, QuotationPricing pricing) {
        List<OfficeDesignFloor> floors = floorMapper.selectList(new QueryWrapper<OfficeDesignFloor>()
            .eq("project_id", project.getProjectId()).orderByAsc("sort_order").orderByAsc("created_at"));
        Map<String, OfficeProductDto> productsById = catalog.loadProducts();
        List<FloorProducts> floorProducts = floors.stream().map(this::collectFloor).toList();
        Set<String> productIds = floorProducts.stream()
            .flatMap(floor -> floor.spaces().stream())
            .flatMap(space -> space.quantities().keySet().stream())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, QuotationCatalog.ProductPrice> prices = catalog.priceProducts(productIds, productsById);

        ProjectQuotationPreviewDto preview = new ProjectQuotationPreviewDto();
        preview.setProjectId(project.getProjectId());
        preview.setProjectName(project.getName());
        preview.setPricingMode(pricing.mode().name());
        preview.setPricingValue(pricing.value());
        preview.setGeneratedAt(LocalDateTime.now());

        QuotationTotalsDto totals = new QuotationTotalsDto();
        for (FloorProducts selection : floorProducts) {
            if (selection.spaces().isEmpty()) {
                continue;
            }
            QuotationFloorDto floor = buildFloor(selection, prices, pricing);
            preview.getFloors().add(floor);
            totals.setFactoryTotal(totals.getFactoryTotal().add(floor.getFactoryTotal()));
            totals.setDealTotal(totals.getDealTotal().add(floor.getDealTotal()));
            for (QuotationSpaceDto space : floor.getSpaces()) {
                totals.setLineCount(totals.getLineCount() + space.getLines().size());
                int missing = (int) space.getLines().stream().filter(line -> line.getIssue() != null).count();
                totals.setMissingFactoryPriceLines(totals.getMissingFactoryPriceLines() + missing);
            }
        }
        preview.setTotals(totals);
        return preview;
    }

    private QuotationFloorDto buildFloor(FloorProducts selection, Map<String, QuotationCatalog.ProductPrice> prices,
                                         QuotationPricing pricing) {
        QuotationFloorDto floor = new QuotationFloorDto();
        floor.setFloorId(selection.floor().getFloorId());
        floor.setFloorName(selection.floor().getName());
        for (SpaceProducts spaceSelection : selection.spaces()) {
            QuotationSpaceDto space = new QuotationSpaceDto();
            space.setZoneId(spaceSelection.zoneId());
            space.setZoneName(spaceSelection.zoneName());
            for (Map.Entry<String, Integer> entry : spaceSelection.quantities().entrySet()) {
                QuotationLineDto line = buildLine(entry.getKey(), entry.getValue(), prices.get(entry.getKey()), pricing);
                space.getLines().add(line);
                if (line.getIssue() == null) {
                    BigDecimal factoryTotal = line.getFactoryPrice().multiply(BigDecimal.valueOf(entry.getValue()));
                    space.setFactoryTotal(space.getFactoryTotal().add(factoryTotal));
                    space.setDealTotal(space.getDealTotal().add(line.getSubtotal()));
                }
            }
            floor.getSpaces().add(space);
            floor.setFactoryTotal(floor.getFactoryTotal().add(space.getFactoryTotal()));
            floor.setDealTotal(floor.getDealTotal().add(space.getDealTotal()));
        }
        return floor;
    }

    private QuotationLineDto buildLine(String productId, int quantity, QuotationCatalog.ProductPrice pricedProduct,
                                       QuotationPricing pricing) {
        QuotationLineDto line = new QuotationLineDto();
        line.setProductId(productId);
        line.setQty(Math.max(quantity, 1));
        line.setProductName(pricedProduct.productName());
        line.setIssue(pricedProduct.issue());
        OfficeProductDto product = pricedProduct.product();
        if (product != null) {
            line.setRspuId(product.getRspuId());
            line.setImageUrl(product.getImageUrl());
            line.setVariant(product.getVariant());
        }
        if (line.getIssue() == null) {
            BigDecimal unitPrice = pricing.unitPrice(pricedProduct.factoryPrice());
            line.setFactoryPrice(pricedProduct.factoryPrice());
            line.setUnitPrice(unitPrice);
            line.setSubtotal(QuotationPricing.subtotal(unitPrice, line.getQty()));
        }
        return line;
    }

    /** 空间有清单时取清单数量，否则按画布家具件数。按来源首次出现的顺序输出。 */
    private FloorProducts collectFloor(OfficeDesignFloor floor) {
        List<OfficeCart> cart = cartMapper.selectList(new QueryWrapper<OfficeCart>()
            .eq("project_id", floor.getFloorId()).orderByAsc("created_at"));
        Map<String, String> zoneNames = new LinkedHashMap<>();
        Map<SpaceProductKey, Integer> furnitureQuantities = new LinkedHashMap<>();
        OfficeCanvas canvas = canvasMapper.selectOne(new QueryWrapper<OfficeCanvas>()
            .eq("project_id", floor.getFloorId()).orderByDesc("created_at").orderByDesc("canvas_id").last("limit 1"));
        if (canvas != null) {
            for (JsonNode node : readObjects(canvas.getObjectsJson())) {
                String kind = node.path("kind").asText();
                if ("zone".equals(kind)) {
                    zoneNames.put(node.path("id").asText(), node.path("name").asText(""));
                } else if ("furniture".equals(kind)) {
                    String productId = node.path("productId").asText("");
                    if (StringUtils.hasText(productId)) {
                        SpaceProductKey key = new SpaceProductKey(node.path("zoneId").asText(""), productId);
                        furnitureQuantities.merge(key, 1, Integer::sum);
                    }
                }
            }
        }

        Map<String, SpaceProducts> spaces = new LinkedHashMap<>();
        for (OfficeCart item : cart) {
            if (!StringUtils.hasText(item.getProductId())) {
                continue;
            }
            String zoneId = item.getZoneId() == null ? "" : item.getZoneId();
            SpaceProducts space = spaces.computeIfAbsent(zoneId, id -> new SpaceProducts(id,
                StringUtils.hasText(item.getZoneName()) ? item.getZoneName()
                    : zoneNames.getOrDefault(id, UNASSIGNED_ZONE_NAME)));
            space.quantities().merge(item.getProductId(), item.getQty() == null ? 1 : item.getQty(), Integer::sum);
        }
        Set<String> cartZones = Set.copyOf(spaces.keySet());
        for (Map.Entry<SpaceProductKey, Integer> entry : furnitureQuantities.entrySet()) {
            SpaceProductKey key = entry.getKey();
            if (cartZones.contains(key.zoneId())) {
                continue;
            }
            SpaceProducts space = spaces.computeIfAbsent(key.zoneId(), id -> new SpaceProducts(id,
                zoneNames.getOrDefault(id, UNASSIGNED_ZONE_NAME)));
            space.quantities().merge(key.productId(), entry.getValue(), Integer::sum);
        }
        return new FloorProducts(floor, new ArrayList<>(spaces.values()));
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

    private record SpaceProductKey(String zoneId, String productId) {
    }

    private record FloorProducts(OfficeDesignFloor floor, List<SpaceProducts> spaces) {
    }

    private record SpaceProducts(String zoneId, String zoneName, Map<String, Integer> quantities) {

        private SpaceProducts(String zoneId, String zoneName) {
            this(zoneId, zoneName, new LinkedHashMap<>());
        }
    }
}
