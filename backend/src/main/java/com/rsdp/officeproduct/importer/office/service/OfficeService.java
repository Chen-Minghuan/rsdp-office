package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.dto.*;
import com.rsdp.officeproduct.importer.office.entity.OfficeAgentMessage;
import com.rsdp.officeproduct.importer.office.entity.OfficeCanvas;
import com.rsdp.officeproduct.importer.office.entity.OfficeCart;
import com.rsdp.officeproduct.importer.office.mapper.OfficeAgentMessageMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCanvasMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCartMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceLockMapper;
import com.rsdp.officeproduct.importer.service.RsdpCatalogGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfficeService {

    private final OfficeWorkspaceLockMapper workspaceLocks;
    private final OfficeProjectService projectService;
    private final OfficeProjectAccessService accessService;
    private final OfficeCanvasMapper canvasMapper;
    private final OfficeCartMapper cartMapper;
    private final OfficeAgentMessageMapper messageMapper;
    private final RsdpCatalogGateway rsdpCatalogGateway;
    private final SharedCatalogService sharedCatalogService;
    private final ObjectMapper objectMapper;

    private static final String DEFAULT_PROJECT_ID = "OFFICE-PROJECT-001";

    public CanvasDto getCanvas(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            projectId = DEFAULT_PROJECT_ID;
        }
        accessService.requireFloorAccess(projectId);
        List<OfficeCanvas> list = canvasMapper.selectList(
            new QueryWrapper<OfficeCanvas>().eq("project_id", projectId).orderByDesc("created_at").orderByDesc("canvas_id").last("limit 1"));
        if (list.isEmpty()) {
            return null;
        }
        return toCanvasDto(list.get(0));
    }

    @Transactional
    public CanvasDto saveCanvas(SaveCanvasRequest request) {
        OfficeLayoutValidationPolicy.requireConfirmedValid(objectMapper.valueToTree(request.getObjects()));
        if (request.getFiles() != null && request.getFiles().size() > 1) {
            throw new BusinessException("一次只能上传一张图纸，请移除多余文件");
        }
        if ("design".equals(request.getMode())) {
            OfficeCanvasReviewPolicy.requireLayoutReady(objectMapper.valueToTree(request.getObjects()), request.getDesignZoneId());
        }
        String projectId = StringUtils.hasText(request.getProjectId()) ? request.getProjectId() : DEFAULT_PROJECT_ID;
        projectService.requireFloor(projectId);
        accessService.requireFloorAccess(projectId);
        workspaceLocks.lockWorkspace(projectId);
        CanvasDto latest = getCanvas(projectId);
        int revision = request.getRevision() != null ? request.getRevision() : 1;
        if (latest != null && latest.getRevision() != null && revision < latest.getRevision()) {
            throw new BusinessException("画布版本已过期，请刷新后重新操作");
        }

        JsonNode objects = objectMapper.valueToTree(request.getObjects());
        maintainSpaceReviewConfirmations(objects, latest);

        // 只在「总览 → 排版」的进入转换上强制全部空间已完成复核确认；
        // 已在排版模式内的继续保存不受此门禁约束（几何失效会把凭据置 STALE，重新进入时再拦截）。
        boolean enteringDesign = "design".equals(request.getMode())
            && (latest == null || !"design".equals(latest.getMode()));
        if (enteringDesign) {
            OfficeCanvasReviewPolicy.requireSpacesReviewConfirmed(objects);
        }

        OfficeCanvas canvas = new OfficeCanvas();
        canvas.setProjectId(projectId);
        canvas.setRevision(request.getRevision() != null ? request.getRevision() : 1);
        canvas.setMode(StringUtils.hasText(request.getMode()) ? request.getMode() : "overview");
        canvas.setDesignZoneId(request.getDesignZoneId());
        canvas.setObjectsJson(writeJson(objects));
        canvas.setFilesJson(writeJson(request.getFiles()));
        canvas.setBlank(request.getBlank() != null ? request.getBlank() : false);
        canvas.setCreatedAt(LocalDateTime.now());
        canvasMapper.insert(canvas);
        return toCanvasDto(canvas);
    }

    /**
     * 设计复核确认状态由服务端维护：普通保存不得携带伪造的“已确认”；
     * 与已确认几何快照逐字段比对，变化置 STALE，恢复吻合则回到 CONFIRMED。
     */
    private void maintainSpaceReviewConfirmations(JsonNode objects, CanvasDto latest) {
        if (objects == null || !objects.isArray()) {
            return;
        }
        Map<String, JsonNode> previousZones = new HashMap<>();
        JsonNode previous = objectMapper.valueToTree(latest != null && latest.getObjects() != null ? latest.getObjects() : List.of());
        if (previous.isArray()) {
            for (JsonNode node : previous) {
                if ("zone".equals(node.path("kind").asText())) {
                    previousZones.put(node.path("id").asText(), node);
                }
            }
        }
        for (JsonNode node : objects) {
            if (!(node instanceof ObjectNode zone) || !"zone".equals(zone.path("kind").asText())) {
                continue;
            }
            JsonNode previousZone = previousZones.get(zone.path("id").asText());
            JsonNode previousConfirmation = previousZone != null ? previousZone.path("reviewConfirmation") : null;
            if (previousConfirmation == null || !previousConfirmation.isObject()) {
                zone.remove("reviewConfirmation");
                continue;
            }
            String status = previousConfirmation.path("status").asText();
            if (!"CONFIRMED".equals(status) && !"STALE".equals(status)) {
                zone.set("reviewConfirmation", previousConfirmation);
                continue;
            }
            boolean unchanged = OfficeSpaceReviewSupport.jsonEquivalent(
                OfficeSpaceReviewSupport.snapshot(objectMapper, zone), previousConfirmation.path("confirmedGeometry"));
            if (unchanged) {
                if ("STALE".equals(status)) {
                    ((ObjectNode) previousConfirmation).put("status", "CONFIRMED");
                }
            } else if ("CONFIRMED".equals(status)) {
                ((ObjectNode) previousConfirmation).put("status", "STALE");
            }
            zone.set("reviewConfirmation", previousConfirmation);
        }
    }

    public List<CartItemDto> listCart(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            projectId = DEFAULT_PROJECT_ID;
        }
        accessService.requireFloorAccess(projectId);
        List<OfficeCart> list = cartMapper.selectList(
            new QueryWrapper<OfficeCart>().eq("project_id", projectId).orderByAsc("created_at"));
        return list.stream().map(this::toCartItemDto).collect(Collectors.toList());
    }

    @Transactional
    public CartItemDto addCartItem(String projectId, CartItemDto dto) {
        accessService.requireFloorAccess(projectId);
        String lineId = StringUtils.hasText(dto.getLineId()) ? dto.getLineId() : UUID.randomUUID().toString().replace("-", "");
        OfficeCart existing = cartMapper.selectById(lineId);
        if (existing != null) {
            existing.setQty(dto.getQty());
            existing.setUpdatedAt(LocalDateTime.now());
            cartMapper.updateById(existing);
            return toCartItemDto(existing);
        }
        OfficeCart cart = new OfficeCart();
        cart.setLineId(lineId);
        cart.setProjectId(projectId);
        cart.setZoneId(dto.getZoneId());
        cart.setZoneName(dto.getZoneName());
        cart.setProductId(dto.getProductId());
        cart.setQty(dto.getQty() != null ? dto.getQty() : 1);
        cart.setCreatedAt(LocalDateTime.now());
        cart.setUpdatedAt(LocalDateTime.now());
        cartMapper.insert(cart);
        return toCartItemDto(cart);
    }

    @Transactional
    public CartItemDto updateCartItemQty(String projectId, String lineId, Integer qty) {
        accessService.requireFloorAccess(projectId);
        OfficeCart cart = cartMapper.selectById(lineId);
        if (cart == null || !cart.getProjectId().equals(projectId)) {
            throw new BusinessException("购物车行不存在");
        }
        if (qty == null || qty <= 0) {
            cartMapper.deleteById(lineId);
            return null;
        }
        cart.setQty(qty);
        cart.setUpdatedAt(LocalDateTime.now());
        cartMapper.updateById(cart);
        return toCartItemDto(cart);
    }

    @Transactional
    public void deleteCartItem(String projectId, String lineId) {
        accessService.requireFloorAccess(projectId);
        OfficeCart cart = cartMapper.selectById(lineId);
        if (cart != null && cart.getProjectId().equals(projectId)) {
            cartMapper.deleteById(lineId);
        }
    }

    @Transactional
    public void clearCart(String projectId) {
        accessService.requireFloorAccess(projectId);
        cartMapper.delete(new QueryWrapper<OfficeCart>().eq("project_id", projectId));
    }

    public List<AgentMessageDto> listMessages(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            projectId = DEFAULT_PROJECT_ID;
        }
        accessService.requireFloorAccess(projectId);
        List<OfficeAgentMessage> list = messageMapper.selectList(
            new QueryWrapper<OfficeAgentMessage>().eq("project_id", projectId).orderByAsc("created_at"));
        return list.stream().map(this::toAgentMessageDto).collect(Collectors.toList());
    }

    @Transactional
    public AgentMessageDto sendMessage(String projectId, SendMessageRequest request) {
        accessService.requireFloorAccess(projectId);
        OfficeAgentMessage userMsg = new OfficeAgentMessage();
        userMsg.setProjectId(projectId);
        userMsg.setRole("user");
        userMsg.setContent(request.getContent());
        userMsg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(userMsg);

        String reply = generateBotReply(projectId, request.getContent());
        OfficeAgentMessage botMsg = new OfficeAgentMessage();
        botMsg.setProjectId(projectId);
        botMsg.setRole("bot");
        botMsg.setContent(reply);
        botMsg.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(botMsg);
        return toAgentMessageDto(botMsg);
    }

    /**
     * 排版选品目录。数据源为主项目（RSDP）商品库的全量有效变体，
     * viewMode 按当前用户两级公共库开关解析（full=全库只读 / own=仅本厂）。
     */
    public List<OfficeProductDto> listOfficeProducts() {
        return rsdpCatalogGateway.listOfficeProducts(sharedCatalogService.resolveViewMode());
    }

    @Transactional
    public void resetProjectData(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            projectId = DEFAULT_PROJECT_ID;
        }
        accessService.requireFloorAccess(projectId);
        canvasMapper.delete(new QueryWrapper<OfficeCanvas>().eq("project_id", projectId));
        cartMapper.delete(new QueryWrapper<OfficeCart>().eq("project_id", projectId));
        messageMapper.delete(new QueryWrapper<OfficeAgentMessage>().eq("project_id", projectId));
    }

    private String generateBotReply(String projectId, String text) {
        String t = text == null ? "" : text;
        if (t.contains("预算") || t.contains("便宜") || t.contains("经济") || t.contains("价格")) {
            return "已记录预算敏感偏好。演示中可先比较候选价格；真实环境将以 RSDP 可售商品、规格与定价策略进行检索，不直接采用模拟价格。";
        }
        if (t.contains("图纸") || t.contains("画布") || t.contains("空间")) {
            return "您可以使用左侧“返回修改画布”继续修改区域或标注，再提交新版需求快照。原有已选商品将保留以便复核。";
        }
        return "已记录您对办公项目的补充说明：“" + t.trim() + "”。\n\n右侧可查看演示商品候选，您可以加入当前区域并调整数量；真实推荐将在接入 RSDP 商品服务与 Office Skill 后实现。";
    }

    private CanvasDto toCanvasDto(OfficeCanvas c) {
        CanvasDto dto = new CanvasDto();
        dto.setCanvasId(c.getCanvasId());
        dto.setProjectId(c.getProjectId());
        dto.setRevision(c.getRevision());
        dto.setMode(c.getMode());
        dto.setDesignZoneId(c.getDesignZoneId());
        dto.setObjects(readJsonList(c.getObjectsJson()));
        dto.setFiles(readJsonList(c.getFilesJson()));
        dto.setBlank(c.getBlank());
        if ("design".equals(dto.getMode())) {
            try {
                OfficeCanvasReviewPolicy.requireLayoutReady(objectMapper.valueToTree(dto.getObjects()), dto.getDesignZoneId());
                OfficeCanvasReviewPolicy.requireSpacesReviewConfirmed(objectMapper.valueToTree(dto.getObjects()));
            } catch (BusinessException invalidDraft) {
                dto.setMode("overview");
                dto.setDesignZoneId(null);
            }
        }
        return dto;
    }

    private CartItemDto toCartItemDto(OfficeCart c) {
        CartItemDto dto = new CartItemDto();
        dto.setLineId(c.getLineId());
        dto.setProjectId(c.getProjectId());
        dto.setZoneId(c.getZoneId());
        dto.setZoneName(c.getZoneName());
        dto.setProductId(c.getProductId());
        dto.setQty(c.getQty());
        return dto;
    }

    private AgentMessageDto toAgentMessageDto(OfficeAgentMessage m) {
        AgentMessageDto dto = new AgentMessageDto();
        dto.setMessageId(m.getMessageId());
        dto.setProjectId(m.getProjectId());
        dto.setRole(m.getRole());
        dto.setContent(m.getContent());
        return dto;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.error("写入 JSON 失败", e);
            return "[]";
        }
    }

    private List<Object> readJsonList(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Object>>() {});
        } catch (Exception e) {
            log.error("读取 JSON 失败: {}", json, e);
            return new ArrayList<>();
        }
    }
}
