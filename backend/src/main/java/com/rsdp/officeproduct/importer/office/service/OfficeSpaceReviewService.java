package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.dto.SpaceReviewConfirmAllResult;
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
import java.util.ArrayList;
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
        AuthoritativeCanvas authoritative = requireAuthoritativeObjects(floorId, request);
        JsonNode objects = authoritative.objects;
        int currentRevision = authoritative.revision;
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
            return currentConfirmation(spaceId, currentRevision, existing);
        }

        OfficeUser user = currentUserService.currentUser();
        ObjectNode confirmation = signConfirmation(currentRevision, user);
        confirmation.set("confirmedGeometry", snapshot);
        target.set("reviewConfirmation", confirmation);

        insertCanvasRevision(authoritative.canvas, objects, currentRevision + 1);

        SpaceReviewConfirmResult result = new SpaceReviewConfirmResult();
        result.setSpaceId(spaceId);
        result.setCanvasRevision(currentRevision + 1);
        result.setStatus("CONFIRMED");
        result.setConfirmedCanvasRevision(currentRevision);
        result.setConfirmedByUserId(user != null ? user.getUserId() : null);
        result.setConfirmedAt(confirmation.get("confirmedAt").asText());
        return result;
    }

    /**
     * 一键复核：对当前楼层全部空间逐签确认凭据。
     * 几何/比例不满足确认条件的空间不阻塞其他空间，逐个列入 failed 并说明原因；
     * 全部已确认时不产生新修订（幂等）。
     */
    @Transactional
    public SpaceReviewConfirmAllResult confirmAll(String floorId, SpaceReviewConfirmRequest request) {
        AuthoritativeCanvas authoritative = requireAuthoritativeObjects(floorId, request);
        JsonNode objects = authoritative.objects;
        int currentRevision = authoritative.revision;

        OfficeUser user = currentUserService.currentUser();
        List<SpaceReviewConfirmResult> confirmed = new ArrayList<>();
        List<SpaceReviewConfirmResult> alreadyConfirmed = new ArrayList<>();
        List<SpaceReviewConfirmAllResult.Failure> failed = new ArrayList<>();
        boolean anyZone = false;
        for (JsonNode node : objects) {
            if (!(node instanceof ObjectNode zone) || !"zone".equals(zone.path("kind").asText())) {
                continue;
            }
            anyZone = true;
            String spaceId = zone.path("id").asText();
            JsonNode snapshot = OfficeSpaceReviewSupport.snapshot(objectMapper, zone);
            JsonNode existing = zone.path("reviewConfirmation");
            if (existing.isObject() && "CONFIRMED".equals(existing.path("status").asText())
                && OfficeSpaceReviewSupport.jsonEquivalent(existing.path("confirmedGeometry"), snapshot)) {
                alreadyConfirmed.add(currentConfirmation(spaceId, currentRevision, existing));
                continue;
            }
            try {
                OfficeCanvasReviewPolicy.requireZoneValid(zone);
            } catch (BusinessException invalid) {
                SpaceReviewConfirmAllResult.Failure failure = new SpaceReviewConfirmAllResult.Failure();
                failure.setSpaceId(spaceId);
                failure.setSpaceName(zone.path("name").asText(""));
                failure.setReason(invalid.getMessage());
                failed.add(failure);
                continue;
            }
            ObjectNode confirmation = signConfirmation(currentRevision, user);
            confirmation.set("confirmedGeometry", snapshot);
            zone.set("reviewConfirmation", confirmation);

            SpaceReviewConfirmResult item = new SpaceReviewConfirmResult();
            item.setSpaceId(spaceId);
            item.setCanvasRevision(currentRevision + 1);
            item.setStatus("CONFIRMED");
            item.setConfirmedCanvasRevision(currentRevision);
            item.setConfirmedByUserId(user != null ? user.getUserId() : null);
            item.setConfirmedAt(confirmation.get("confirmedAt").asText());
            confirmed.add(item);
        }
        if (!anyZone) {
            throw new BusinessException("请先识别或创建空间");
        }

        SpaceReviewConfirmAllResult result = new SpaceReviewConfirmAllResult();
        if (!confirmed.isEmpty()) {
            insertCanvasRevision(authoritative.canvas, objects, currentRevision + 1);
            result.setCanvasRevision(currentRevision + 1);
        } else {
            result.setCanvasRevision(currentRevision);
        }
        result.setConfirmed(confirmed);
        result.setAlreadyConfirmed(alreadyConfirmed);
        result.setFailed(failed);
        return result;
    }

    /** 校验楼层/访问/版本后的权威画布；复核确认只基于服务端最新画布签发。 */
    private record AuthoritativeCanvas(OfficeCanvas canvas, int revision, JsonNode objects) {}

    /** 校验楼层/访问/版本并返回当前权威画布对象；复核确认只基于服务端最新画布签发。 */
    private AuthoritativeCanvas requireAuthoritativeObjects(String floorId, SpaceReviewConfirmRequest request) {
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
        return new AuthoritativeCanvas(latest, currentRevision, objects);
    }

    private ObjectNode signConfirmation(int currentRevision, OfficeUser user) {
        ObjectNode confirmation = objectMapper.createObjectNode();
        confirmation.put("status", "CONFIRMED");
        confirmation.put("confirmedCanvasRevision", currentRevision);
        if (user != null) {
            confirmation.put("confirmedByUserId", user.getUserId());
        }
        confirmation.put("confirmedAt", Instant.now().toString());
        return confirmation;
    }

    private SpaceReviewConfirmResult currentConfirmation(String spaceId, int currentRevision, JsonNode existing) {
        SpaceReviewConfirmResult current = new SpaceReviewConfirmResult();
        current.setSpaceId(spaceId);
        current.setCanvasRevision(currentRevision);
        current.setStatus("CONFIRMED");
        current.setConfirmedCanvasRevision(intOrNull(existing.path("confirmedCanvasRevision")));
        current.setConfirmedByUserId(textOrNull(existing.path("confirmedByUserId")));
        current.setConfirmedAt(textOrNull(existing.path("confirmedAt")));
        return current;
    }

    private void insertCanvasRevision(OfficeCanvas latest, JsonNode objects, int revision) {
        OfficeCanvas canvas = new OfficeCanvas();
        canvas.setProjectId(latest.getProjectId());
        canvas.setRevision(revision);
        canvas.setMode(latest.getMode());
        canvas.setDesignZoneId(latest.getDesignZoneId());
        canvas.setObjectsJson(writeJson(objects));
        canvas.setFilesJson(latest.getFilesJson());
        canvas.setBlank(latest.getBlank());
        canvas.setCreatedAt(LocalDateTime.now());
        canvasMapper.insert(canvas);
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
