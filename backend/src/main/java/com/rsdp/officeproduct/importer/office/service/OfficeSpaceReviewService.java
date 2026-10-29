package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.dto.SpaceReviewConfirmRequest;
import com.rsdp.officeproduct.importer.office.dto.SpaceReviewConfirmResult;
import com.rsdp.officeproduct.importer.office.entity.OfficeCanvas;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCanvasMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceLockMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 设计空间复核确认（SPACE_REVIEW）：服务端基于当前权威画布签发确认凭据。
 * 与家具排版确认（开发 C）、客户方案确认（开发 E）相互独立。
 */
@Service
@RequiredArgsConstructor
public class OfficeSpaceReviewService {

    private final OfficeProjectService projectService;
    private final OfficeProjectAccessService accessService;
    private final CurrentUserService currentUserService;
    private final OfficeCanvasMapper canvasMapper;
    private final OfficeWorkspaceLockMapper workspaceLocks;
    private final ObjectMapper objectMapper;

    @Transactional
    public SpaceReviewConfirmResult confirm(String floorId, String spaceId, SpaceReviewConfirmRequest request) {
        projectService.requireFloor(floorId);
        accessService.requireFloorAccess(floorId);
        workspaceLocks.lockWorkspace(floorId);

        OfficeCanvas latest = latestCanvas(floorId);
        if (latest == null) {
            throw new BusinessException("画布尚未保存，请先保存空间后再确认复核");
        }
        int currentRevision = latest.getRevision() != null ? latest.getRevision() : 0;
        if (request == null || request.getBaseCanvasRevision() == null
            || request.getBaseCanvasRevision().intValue() != currentRevision) {
            throw new BusinessException("画布版本已过期（当前修订 " + currentRevision + "），请刷新后重新确认");
        }
        JsonNode objects = readTreeQuietly(latest.getObjectsJson());
        if (objects == null || !objects.isArray()) {
            throw new BusinessException("画布数据无效，请刷新后重试");
        }
        ObjectNode target = null;
        for (JsonNode node : objects) {
            if (node instanceof ObjectNode objectNode
                && "zone".equals(node.path("kind").asText())
                && node.path("id").asText().equals(spaceId)) {
                target = objectNode;
                break;
            }
        }
        if (target == null) {
            throw new BusinessException("空间不存在或已被删除");
        }
        OfficeCanvasReviewPolicy.requireZoneValid(target);

        JsonNode snapshot = OfficeSpaceReviewSupport.snapshot(objectMapper, target);
        JsonNode existing = target.path("reviewConfirmation");
        if (existing.isObject() && "CONFIRMED".equals(existing.path("status").asText())
            && OfficeSpaceReviewSupport.jsonEquivalent(existing.path("confirmedGeometry"), snapshot)) {
            // 同一未变更空间重复确认：返回当前有效确认，不产生无意义新修订。
            SpaceReviewConfirmResult current = new SpaceReviewConfirmResult();
            current.setSpaceId(spaceId);
            current.setCanvasRevision(currentRevision);
            current.setStatus("CONFIRMED");
            current.setConfirmedCanvasRevision(intOrNull(existing.path("confirmedCanvasRevision")));
            current.setConfirmedByUserId(textOrNull(existing.path("confirmedByUserId")));
            current.setConfirmedAt(textOrNull(existing.path("confirmedAt")));
            return current;
        }

        OfficeUser user = currentUserService.currentUser();
        ObjectNode confirmation = objectMapper.createObjectNode();
        confirmation.put("status", "CONFIRMED");
        confirmation.put("confirmedCanvasRevision", currentRevision);
        if (user != null) {
            confirmation.put("confirmedByUserId", user.getUserId());
        }
        confirmation.put("confirmedAt", Instant.now().toString());
        confirmation.set("confirmedGeometry", snapshot);
        target.set("reviewConfirmation", confirmation);

        OfficeCanvas canvas = new OfficeCanvas();
        canvas.setProjectId(floorId);
        canvas.setRevision(currentRevision + 1);
        canvas.setMode(latest.getMode());
        canvas.setDesignZoneId(latest.getDesignZoneId());
        canvas.setObjectsJson(writeJson(objects));
        canvas.setFilesJson(latest.getFilesJson());
        canvas.setBlank(latest.getBlank());
        canvas.setCreatedAt(LocalDateTime.now());
        canvasMapper.insert(canvas);

        SpaceReviewConfirmResult result = new SpaceReviewConfirmResult();
        result.setSpaceId(spaceId);
        result.setCanvasRevision(currentRevision + 1);
        result.setStatus("CONFIRMED");
        result.setConfirmedCanvasRevision(currentRevision);
        result.setConfirmedByUserId(user != null ? user.getUserId() : null);
        result.setConfirmedAt(confirmation.get("confirmedAt").asText());
        return result;
    }

    private OfficeCanvas latestCanvas(String floorId) {
        List<OfficeCanvas> list = canvasMapper.selectList(new QueryWrapper<OfficeCanvas>()
            .eq("project_id", floorId).orderByDesc("created_at").orderByDesc("canvas_id").last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private JsonNode readTreeQuietly(String json) {
        try {
            return objectMapper.readTree(json == null ? "[]" : json);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new BusinessException("画布数据无法保存", e);
        }
    }

    private Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.intValue() : null;
    }

    private String textOrNull(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }
}
