package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.dto.*;
import com.rsdp.officeproduct.importer.office.entity.OfficeCart;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCartMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceLockMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class OfficeModuleRecommendationService {
    private final OfficeService office;
    private final OfficeWorkspaceLockMapper workspaceLocks;
    private final OfficeCartMapper carts;
    private final ObjectMapper json;

    private record Context(CanvasDto canvas, List<JsonNode> objects, JsonNode module, List<JsonNode> sameModules) {}

    private Context context(String projectId, ModuleRecommendationRequest request) {
        if (request == null || request.getZoneId() == null || request.getModuleId() == null || request.getCanvasRevision() == null) throw new BusinessException("请选择已确认的单位模块");
        CanvasDto canvas = office.getCanvas(projectId);
        if (canvas == null || !Objects.equals(canvas.getRevision(), request.getCanvasRevision())) throw new BusinessException("排版版本已变化，请重新读取推荐");
        JsonNode array = json.valueToTree(canvas.getObjects());
        OfficeCanvasReviewPolicy.requireLayoutReady(array, request.getZoneId());
        List<JsonNode> objects = new ArrayList<>(); array.forEach(objects::add);
        List<JsonNode> modules = objects.stream().filter(item -> "furniture".equals(item.path("kind").asText()) && request.getZoneId().equals(item.path("zoneId").asText())).toList();
        Set<String> ids = new HashSet<>();
        if (modules.isEmpty() || modules.stream().anyMatch(item -> !item.path("layoutConfirmed").asBoolean() || item.path("id").asText().isBlank() || !ids.add(item.path("id").asText()))) throw new BusinessException("请先确认当前空间的全部排版");
        JsonNode module = modules.stream().filter(item -> request.getModuleId().equals(item.path("id").asText())).findFirst().orElseThrow(() -> new BusinessException("当前空间不存在此单位模块"));
        if (OfficeProductMatching.positive(module.get("w")) == null || OfficeProductMatching.positive(module.get("h")) == null) throw new BusinessException("单位模块的毫米尺寸无效");
        List<JsonNode> same = modules.stream().filter(item -> sameModule(module, item)).toList();
        return new Context(canvas, objects, module, same);
    }

    private boolean sameModule(JsonNode a, JsonNode b) {
        return Objects.equals(a.path("furnitureTemplateId"), b.path("furnitureTemplateId"))
            && Objects.equals(a.path("moduleSnapshot"), b.path("moduleSnapshot"))
            && Objects.equals(a.path("heightMm"), b.path("heightMm"))
            && a.path("w").decimalValue().compareTo(b.path("w").decimalValue()) == 0
            && a.path("h").decimalValue().compareTo(b.path("h").decimalValue()) == 0;
    }

    private ModuleRecommendationDto recommendations(Context context, ModuleRecommendationRequest request) {
        BigDecimal width = OfficeProductMatching.positive(context.module().get("w"));
        BigDecimal depth = OfficeProductMatching.positive(context.module().get("h"));
        List<ModuleRecommendationDto.Candidate> matches = new ArrayList<>();
        int missing = 0;
        List<OfficeProductDto> catalog = office.listOfficeProducts();
        for (OfficeProductDto product : catalog) {
            if (product.getWidthMm() == null || product.getDepthMm() == null) { missing++; continue; }
            var match = OfficeProductMatching.match(product, width, depth, request.isAllowRotation());
            if (match != null) matches.add(match);
        }
        matches.sort(Comparator.comparing(ModuleRecommendationDto.Candidate::getFitRatio).reversed()
            .thenComparingInt(ModuleRecommendationDto.Candidate::getRotationDeg)
            .thenComparing(item -> item.getProduct().getId()));
        ModuleRecommendationDto result = new ModuleRecommendationDto();
        result.setModuleId(request.getModuleId()); result.setZoneId(request.getZoneId());
        result.setCanvasRevision(context.canvas().getRevision()); result.setWidthMm(width); result.setDepthMm(depth);
        result.setSameModuleCount(context.sameModules().size()); result.setCandidates(matches); result.setMissingDimensions(missing); result.setEligibleVariantCount(catalog.size());
        return result;
    }

    public ModuleRecommendationDto recommend(String projectId, ModuleRecommendationRequest request) {
        return recommendations(context(projectId, request), request);
    }

    /** Recheck saved geometry and live SKU availability at selection time, then bind without resizing reservations. */
    @Transactional
    public ModuleRecommendationDto.Selection select(String projectId, ModuleRecommendationRequest request) {
        workspaceLocks.lockWorkspace(projectId);
        Context context = context(projectId, request);
        var result = recommendations(context, request);
        var match = result.getCandidates().stream().filter(candidate -> Objects.equals(candidate.getProduct().getId(), request.getProductId())).findFirst()
            .orElseThrow(() -> new BusinessException("商品已不可用、尺寸缺失或超出当前模块，请重新选择"));
        if (match.getProduct().getPrice() == null || match.getProduct().getPrice().signum() <= 0) throw new BusinessException("商品尚未报价，请补齐报价后加入方案");
        List<JsonNode> targets = request.isApplyToSameModules() ? context.sameModules() : List.of(context.module());
        List<CartItemDto> lines = new ArrayList<>();
        String zoneName = context.objects().stream().filter(item -> request.getZoneId().equals(item.path("id").asText()) && "zone".equals(item.path("kind").asText())).findFirst().orElseThrow().path("name").asText();
        for (JsonNode target : targets) {
            String lineId = "module-" + UUID.nameUUIDFromBytes((projectId + ":" + target.path("id").asText()).getBytes(StandardCharsets.UTF_8));
            OfficeCart line = carts.selectById(lineId);
            boolean exists = line != null;
            if (!exists) { line = new OfficeCart(); line.setLineId(lineId); line.setCreatedAt(LocalDateTime.now()); }
            line.setProjectId(projectId); line.setZoneId(request.getZoneId()); line.setZoneName(zoneName);
            line.setProductId(match.getProduct().getId()); line.setQty(1); line.setUpdatedAt(LocalDateTime.now());
            if (exists) carts.updateById(line); else carts.insert(line);
            ObjectNode object = (ObjectNode) target;
            object.put("productId", match.getProduct().getId());
            ObjectNode selection = json.valueToTree(match);
            selection.put("cartLineId", lineId); selection.put("sourceCanvasRevision", context.canvas().getRevision());
            object.set("productSelection", selection);
            CartItemDto dto = new CartItemDto(); dto.setLineId(lineId); dto.setProjectId(projectId); dto.setZoneId(request.getZoneId());
            dto.setZoneName(zoneName); dto.setProductId(line.getProductId()); dto.setQty(1); lines.add(dto);
        }
        SaveCanvasRequest save = new SaveCanvasRequest();
        save.setProjectId(projectId); save.setRevision(context.canvas().getRevision() + 1);
        save.setMode(context.canvas().getMode()); save.setDesignZoneId(context.canvas().getDesignZoneId());
        save.setObjects(new ArrayList<Object>(context.objects())); save.setFiles(context.canvas().getFiles()); save.setBlank(context.canvas().getBlank());
        ModuleRecommendationDto.Selection selection = new ModuleRecommendationDto.Selection();
        selection.setCanvas(office.saveCanvas(save)); selection.setCartItems(lines); selection.setMatch(match);
        return selection;
    }
}
