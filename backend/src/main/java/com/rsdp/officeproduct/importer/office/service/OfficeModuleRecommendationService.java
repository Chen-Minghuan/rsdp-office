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
        List<JsonNode> modules = confirmedModules(objects, request.getZoneId());
        JsonNode module = modules.stream().filter(item -> request.getModuleId().equals(item.path("id").asText())).findFirst().orElseThrow(() -> new BusinessException("当前空间不存在此单位模块"));
        if (OfficeProductMatching.positive(module.get("w")) == null || OfficeProductMatching.positive(module.get("h")) == null) throw new BusinessException("单位模块的毫米尺寸无效");
        List<JsonNode> same = modules.stream().filter(item -> sameModule(module, item)).toList();
        return new Context(canvas, objects, module, same);
    }

    private List<JsonNode> confirmedModules(List<JsonNode> objects, String zoneId) {
        List<JsonNode> modules = objects.stream().filter(item -> "furniture".equals(item.path("kind").asText())
            && zoneId.equals(item.path("zoneId").asText())).toList();
        Set<String> ids = new HashSet<>();
        if (modules.isEmpty() || modules.stream().anyMatch(item -> !item.path("layoutConfirmed").asBoolean()
            || item.path("id").asText().isBlank() || !ids.add(item.path("id").asText()))) {
            throw new BusinessException("请先确认当前空间的全部排版");
        }
        return modules;
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
        var target = OfficeFurnitureRelevance.target(context.module());
        List<ModuleRecommendationDto.Candidate> matches = new ArrayList<>();
        int missing = 0;
        List<OfficeProductDto> catalog = target == null ? List.of() : office.listOfficeProducts();
        for (OfficeProductDto product : catalog) {
            if (product.getWidthMm() == null || product.getDepthMm() == null) { missing++; continue; }
            var match = OfficeProductMatching.match(product, width, depth, request.isAllowRotation());
            if (match == null) continue;
            var relevance = OfficeFurnitureRelevance.match(target, product);
            if (relevance == null) continue;
            match.setMatchReason(relevance.reason());
            // The 0–15 size contribution sorts offers with comparable category evidence.
            match.setRelevanceScore(BigDecimal.valueOf(relevance.points()).add(match.getFitRatio().multiply(BigDecimal.valueOf(15))));
            matches.add(match);
        }
        matches.sort(Comparator.comparing(ModuleRecommendationDto.Candidate::getRelevanceScore).reversed()
            .thenComparing(ModuleRecommendationDto.Candidate::getFitRatio, Comparator.reverseOrder())
            .thenComparingInt(ModuleRecommendationDto.Candidate::getRotationDeg)
            .thenComparing(item -> item.getProduct().getId()));
        ModuleRecommendationDto result = new ModuleRecommendationDto();
        result.setModuleId(request.getModuleId()); result.setZoneId(request.getZoneId());
        result.setCanvasRevision(context.canvas().getRevision()); result.setWidthMm(width); result.setDepthMm(depth);
        result.setTargetCategory(target == null ? null : target.label);
        result.setSameModuleCount(context.sameModules().size());
        result.setCandidates(matches); result.setMissingDimensions(missing); result.setEligibleVariantCount(catalog.size());
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
        if (!Objects.equals(context.canvas().getSchemeToken(), request.getSchemeToken())) {
            throw new BusinessException("房间方案已变化，请刷新选品后重试");
        }
        var result = recommendations(context, request);
        var match = result.getCandidates().stream().filter(candidate -> Objects.equals(candidate.getProduct().getId(), request.getProductId())).findFirst()
            .orElseThrow(() -> new BusinessException("商品已不可用，或品类、尺寸与当前模块不匹配，请重新选择"));
        var current = office.currentProductVariant(match.getProduct().getRspuId(), match.getProduct().getId());
        if (current == null) {
            throw new BusinessException("商品已不可用，请刷新推荐并重新选择");
        }
        var representativeMatch = OfficeProductMatching.match(current, result.getWidthMm(), result.getDepthMm(), request.isAllowRotation());
        var relevanceTarget = OfficeFurnitureRelevance.target(context.module());
        var relevance = relevanceTarget == null ? null : OfficeFurnitureRelevance.match(relevanceTarget, current);
        if (representativeMatch == null || relevance == null) {
            throw new BusinessException("商品规格或品类已变化，请刷新推荐并重新选择");
        }
        representativeMatch.setMatchReason(relevance.reason());
        representativeMatch.setRelevanceScore(BigDecimal.valueOf(relevance.points()).add(representativeMatch.getFitRatio().multiply(BigDecimal.valueOf(15))));
        match = representativeMatch;
        if (match.getProduct().getPrice() == null || match.getProduct().getPrice().signum() <= 0) throw new BusinessException("商品尚未报价，请补齐报价后加入方案");
        List<JsonNode> targets = request.isApplyToSameModules() ? context.sameModules() : List.of(context.module());
        List<CartItemDto> lines = new ArrayList<>();
        String zoneName = context.objects().stream().filter(item -> request.getZoneId().equals(item.path("id").asText()) && "zone".equals(item.path("kind").asText())).findFirst().orElseThrow().path("name").asText();
        for (JsonNode target : targets) {
            lines.add(bind(projectId, request.getZoneId(), zoneName, target, match, context.canvas().getRevision()));
        }
        ModuleRecommendationDto.Selection selection = new ModuleRecommendationDto.Selection();
        selection.setCanvas(office.saveCanvas(nextCanvas(context.canvas(), context.objects())));
        selection.setCartItems(lines); selection.setMatch(match);
        return selection;
    }

    @Transactional
    public OfficeSchemeDtos.Result applyRoomProducts(String floorId, OfficeProductBundleDtos.Apply request) {
        if (request == null || request.zoneId() == null || request.revision() == null) throw new BusinessException("请选择当前空间");
        workspaceLocks.lockWorkspace(floorId);
        CanvasDto canvas = office.getCanvas(floorId);
        if (canvas == null || !Objects.equals(canvas.getRevision(), request.revision())
            || !Objects.equals(canvas.getSchemeToken(), request.schemeToken())) throw new BusinessException("房间方案已变化，请刷新后重试");
        JsonNode array = json.valueToTree(canvas.getObjects());
        OfficeCanvasReviewPolicy.requireSpacesReviewConfirmed(array);
        OfficeCanvasReviewPolicy.requireLayoutReady(array, request.zoneId());
        List<JsonNode> objects = new ArrayList<>();
        array.forEach(objects::add);
        JsonNode zone = objects.stream().filter(item -> "zone".equals(item.path("kind").asText())
            && request.zoneId().equals(item.path("id").asText())).findFirst().orElseThrow();
        if ("DEMO".equals(zone.path("evidenceKind").asText())) throw new BusinessException("演示空间不能批量绑定正式商品");
        List<JsonNode> available = confirmedModules(objects, request.zoneId()).stream()
            .filter(item -> item.path("productId").asText().isBlank() && !item.hasNonNull("productSelection")).toList();
        if (available.isEmpty()) throw new BusinessException("当前房间没有待选品家具");
        var matches = request.automatic() ? automaticMatches(available) : explicitMatches(available, request.items());
        for (var entry : matches.entrySet()) {
            bind(floorId, request.zoneId(), zone.path("name").asText(), entry.getKey(), entry.getValue(), canvas.getRevision());
        }
        CanvasDto saved = office.replaceSchemeCanvas(nextCanvas(canvas, objects));
        return new OfficeSchemeDtos.Result(saved, office.listCart(floorId), null);
    }

    private Map<JsonNode, ModuleRecommendationDto.Candidate> automaticMatches(List<JsonNode> modules) {
        var catalog = office.listOfficeProducts();
        Map<List<String>, Optional<OfficeProductDto>> current = new HashMap<>();
        Map<JsonNode, ModuleRecommendationDto.Candidate> result = new LinkedHashMap<>();
        for (JsonNode module : modules) {
            var matches = catalog.stream().filter(product -> product.getRspuId() != null && product.getId() != null)
                .map(product -> candidate(module, product)).filter(Objects::nonNull)
                .sorted(Comparator.comparing(ModuleRecommendationDto.Candidate::getRelevanceScore).reversed()
                    .thenComparing(match -> match.getProduct().getId())).toList();
            for (var match : matches) {
                var product = match.getProduct();
                var live = current.computeIfAbsent(List.of(product.getRspuId(), product.getId()),
                    key -> Optional.ofNullable(office.currentProductVariant(key.get(0), key.get(1))));
                var verified = live.map(item -> candidate(module, item)).orElse(null);
                if (verified != null) {
                    result.put(module, verified);
                    break;
                }
            }
        }
        if (result.isEmpty()) throw new BusinessException("没有找到规格匹配且已报价的商品");
        return result;
    }

    private Map<JsonNode, ModuleRecommendationDto.Candidate> explicitMatches(List<JsonNode> modules, List<OfficeProductBundleDtos.Item> items) {
        OfficeProductBundleService.validateItems(items, false);
        if (items.stream().mapToInt(OfficeProductBundleDtos.Item::quantity).sum() > modules.size()) {
            throw new BusinessException("组合商品数量超过当前房间的待选品家具数量");
        }
        List<List<ModuleRecommendationDto.Candidate>> choices = new ArrayList<>();
        for (var item : items) {
            var product = office.currentProductVariant(item.rspuId(), item.variantId());
            if (product == null) throw new BusinessException("有商品变体已不可用，请调整后重试");
            var candidates = modules.stream().map(module -> candidate(module, product)).toList();
            for (int copy = 0; copy < item.quantity(); copy++) choices.add(candidates);
        }
        int[] owners = new int[modules.size()];
        Arrays.fill(owners, -1);
        for (int index = 0; index < choices.size(); index++) {
            if (!assign(index, choices, owners, new boolean[owners.length])) throw new BusinessException("待选品家具的品类或尺寸无法容纳全部商品");
        }
        Map<JsonNode, ModuleRecommendationDto.Candidate> result = new LinkedHashMap<>();
        for (int index = 0; index < owners.length; index++) {
            if (owners[index] >= 0) result.put(modules.get(index), choices.get(owners[index]).get(index));
        }
        return result;
    }

    private boolean assign(int item, List<List<ModuleRecommendationDto.Candidate>> choices, int[] owners, boolean[] visited) {
        for (int module = 0; module < owners.length; module++) {
            if (visited[module] || choices.get(item).get(module) == null) continue;
            visited[module] = true;
            if (owners[module] < 0 || assign(owners[module], choices, owners, visited)) {
                owners[module] = item;
                return true;
            }
        }
        return false;
    }

    private ModuleRecommendationDto.Candidate candidate(JsonNode module, OfficeProductDto product) {
        var target = OfficeFurnitureRelevance.target(module);
        var width = OfficeProductMatching.positive(module.get("w"));
        var depth = OfficeProductMatching.positive(module.get("h"));
        if (target == null || width == null || depth == null || product.getPrice() == null || product.getPrice().signum() <= 0) return null;
        var relevance = OfficeFurnitureRelevance.match(target, product);
        var match = OfficeProductMatching.match(product, width, depth, true);
        if (relevance == null || match == null) return null;
        match.setMatchReason(relevance.reason());
        match.setRelevanceScore(BigDecimal.valueOf(relevance.points()).add(match.getFitRatio().multiply(BigDecimal.valueOf(15))));
        return match;
    }

    private CartItemDto bind(String projectId, String zoneId, String zoneName, JsonNode target, ModuleRecommendationDto.Candidate match, int revision) {
        String lineId = "module-" + UUID.nameUUIDFromBytes((projectId + ":" + target.path("id").asText()).getBytes(StandardCharsets.UTF_8));
        OfficeCart line = carts.selectById(lineId);
        boolean exists = line != null;
        if (!exists) {
            line = new OfficeCart();
            line.setLineId(lineId);
            line.setCreatedAt(LocalDateTime.now());
        } else if (!projectId.equals(line.getProjectId())) {
            throw new BusinessException("选品清单行归属与当前楼层不一致");
        }
        line.setProjectId(projectId);
        line.setZoneId(zoneId);
        line.setZoneName(zoneName);
        line.setProductId(match.getProduct().getId());
        line.setQty(1);
        line.setUpdatedAt(LocalDateTime.now());
        if (exists) carts.updateById(line); else carts.insert(line);
        ObjectNode object = (ObjectNode) target;
        object.put("productId", match.getProduct().getId());
        ObjectNode selection = json.valueToTree(match);
        selection.put("cartLineId", lineId);
        selection.put("sourceCanvasRevision", revision);
        object.set("productSelection", selection);
        CartItemDto dto = new CartItemDto();
        dto.setLineId(lineId);
        dto.setProjectId(projectId);
        dto.setZoneId(zoneId);
        dto.setZoneName(zoneName);
        dto.setProductId(line.getProductId());
        dto.setQty(1);
        return dto;
    }

    private SaveCanvasRequest nextCanvas(CanvasDto canvas, List<JsonNode> objects) {
        SaveCanvasRequest save = new SaveCanvasRequest();
        save.setProjectId(canvas.getProjectId()); save.setRevision(canvas.getRevision() + 1);
        save.setSchemeToken(canvas.getSchemeToken());
        save.setMode(canvas.getMode()); save.setDesignZoneId(canvas.getDesignZoneId());
        save.setObjects(new ArrayList<Object>(objects)); save.setFiles(canvas.getFiles()); save.setBlank(canvas.getBlank());
        return save;
    }
}
