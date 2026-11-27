package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.dto.CanvasDto;
import com.rsdp.officeproduct.importer.office.dto.CartItemDto;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProposalConfirmationMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeRoomSchemeMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceLockMapper;
import com.rsdp.officeproduct.importer.service.VisionImagePreparer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OfficeProposalConfirmationService {
    public record Request(Integer canvasRevision, String schemeToken) {}
    public record Status(String confirmationId, int canvasRevision, LocalDateTime confirmedAt, boolean current) {}
    private record Snapshot(int revision, String json, String fingerprint) {}

    private final OfficeService office;
    private final OfficeProjectAccessService access;
    private final OfficeWorkspaceLockMapper locks;
    private final OfficeProposalConfirmationMapper confirmations;
    private final OfficeRoomSchemeMapper roomSchemes;
    private final ObjectMapper json;

    @Transactional
    public Status confirm(String floorId, Request request) {
        access.requireFloorPermission(floorId, OfficeProjectPermission.EDIT);
        locks.lockWorkspace(floorId);
        CanvasDto canvas = office.getCanvas(floorId);
        if (request == null || canvas == null || !Objects.equals(canvas.getRevision(), request.canvasRevision())
            || !Objects.equals(canvas.getSchemeToken(), request.schemeToken())) {
            throw new BusinessException("当前方案已变化，请保存或刷新后重新确认");
        }
        JsonNode objects = json.valueToTree(canvas.getObjects());
        OfficeCanvasReviewPolicy.requireSpacesReviewConfirmed(objects);
        OfficeLayoutValidationPolicy.requireConfirmedValid(objects);
        int furnitureCount = 0;
        for (JsonNode item : objects) {
            if ("zone".equals(item.path("kind").asText()) && "DEMO".equals(item.path("evidenceKind").asText())) {
                throw new BusinessException("演示空间不能进入正式方案");
            }
            if (!"furniture".equals(item.path("kind").asText())) continue;
            furnitureCount++;
            JsonNode product = item.path("productSelection").path("product");
            if (!item.path("layoutConfirmed").asBoolean() || product.path("rspuId").asText().isBlank()
                || product.path("id").asText().isBlank() || !product.path("id").asText().equals(item.path("productId").asText())
                || OfficeProductMatching.positive(product.get("price")) == null) {
                throw new BusinessException("请先确认全部排版，并为家具绑定有效的已报价商品变体");
            }
        }
        if (furnitureCount == 0) throw new BusinessException("当前楼层没有可确认的家具方案");
        var cart = office.listCart(floorId);
        if (cart.stream().anyMatch(line -> line.getZoneId() == null || line.getZoneId().isBlank())) {
            throw new BusinessException("请先处理清单中未分区的商品");
        }
        office.checkProposalCatalog(floorId);
        requireSchemeChoicesConfirmed(floorId, objects);
        Snapshot snapshot = snapshot(canvas, cart);
        var previous = confirmations.latest(floorId);
        if (current(previous, snapshot)) return status(previous, true);
        var row = new OfficeProposalConfirmationMapper.Row("PROP-" + UUID.randomUUID(), floorId, snapshot.revision(),
            snapshot.fingerprint(), snapshot.json(), access.currentUserId(), LocalDateTime.now(), false);
        confirmations.insert(row);
        return status(row, true);
    }

    public Status latest(String floorId) {
        access.requireFloorAccess(floorId);
        var row = confirmations.latest(floorId);
        if (row == null) return null;
        CanvasDto canvas = office.getCanvas(floorId);
        if (row.retired() || canvas == null || !Objects.equals(canvas.getRevision(), row.canvasRevision())) return status(row, false);
        try {
            return status(row, current(row, snapshot(canvas, office.listCart(floorId))));
        } catch (BusinessException invalidLayout) {
            return status(row, false);
        }
    }

    /** Caller holds the floor lock. Only a current, still-priced confirmed scheme can be sent to the renderer. */
    public JsonNode requireRenderScene(String floorId, String zoneId, String confirmationId) {
        Status latest = latest(floorId);
        if (latest == null || !latest.current() || !latest.confirmationId().equals(confirmationId)) {
            throw new BusinessException("请在项目清单确认当前方案后重新生成效果图");
        }
        office.checkProposalCatalog(floorId);
        var row = confirmations.latest(floorId);
        try {
            JsonNode scene = json.readTree(row.snapshotJson()).path("rooms").path(zoneId).path("scene");
            if (scene.isMissingNode()) throw new BusinessException("已确认方案不包括当前房间");
            return RenderPreviewScene.canonical(scene);
        } catch (JsonProcessingException error) {
            throw new BusinessException("已确认的方案快照无法读取");
        }
    }

    /** 已创建 Plan B 的空间必须先确认使用 Plan A 还是 Plan B，才能确认楼层方案。 */
    private void requireSchemeChoicesConfirmed(String floorId, JsonNode objects) {
        var unconfirmed = roomSchemes.findUnconfirmedZoneIds(floorId);
        if (unconfirmed.isEmpty()) return;
        var names = new java.util.HashMap<String, String>();
        for (JsonNode item : objects) {
            if ("zone".equals(item.path("kind").asText())) names.put(item.path("id").asText(), item.path("name").asText());
        }
        var labels = unconfirmed.stream().map(id -> {
            String name = names.get(id);
            return name == null || name.isBlank() ? id : name;
        }).toList();
        throw new BusinessException("还有 " + labels.size() + " 个空间未确认使用 Plan A 或 Plan B：" + String.join("、", labels));
    }

    private boolean current(OfficeProposalConfirmationMapper.Row row, Snapshot snapshot) {
        return row != null && !row.retired() && row.canvasRevision() == snapshot.revision() && row.fingerprint().equals(snapshot.fingerprint());
    }

    private Status status(OfficeProposalConfirmationMapper.Row row, boolean current) {
        return new Status(row.confirmationId(), row.canvasRevision(), row.confirmedAt(), current);
    }

    private Snapshot snapshot(CanvasDto canvas, java.util.List<CartItemDto> cart) {
        JsonNode objects = json.valueToTree(canvas.getObjects());
        ObjectNode snapshot = json.createObjectNode();
        snapshot.put("schemeToken", canvas.getSchemeToken());
        ObjectNode rooms = snapshot.putObject("rooms");
        var furniture = new ArrayList<JsonNode>();
        for (JsonNode item : objects) {
            if ("zone".equals(item.path("kind").asText())) {
                String id = item.path("id").asText();
                if (id.isBlank() || rooms.has(id)) throw new BusinessException("方案有无效空间 ID");
                ObjectNode room = rooms.putObject(id);
                room.set("scene", RenderPreviewScene.snapshot(objects, id));
                room.set("geometry", OfficeSpaceReviewSupport.snapshot(json, item));
                room.put("review", item.path("reviewConfirmation").path("status").asText());
            } else if ("furniture".equals(item.path("kind").asText())) {
                furniture.add(item);
            }
        }
        ObjectNode confirmationsByFurniture = snapshot.putObject("layoutConfirmed");
        for (JsonNode item : furniture) {
            String id = item.path("id").asText();
            if (id.isBlank() || confirmationsByFurniture.has(id) || !rooms.has(item.path("zoneId").asText())) {
                throw new BusinessException("有家具缺少有效空间或家具 ID");
            }
            confirmationsByFurniture.put(id, item.path("layoutConfirmed").asBoolean());
        }
        var lines = snapshot.putArray("cart");
        cart.stream().sorted(Comparator.comparing(CartItemDto::getLineId)).forEach(item -> {
            var line = lines.addObject();
            line.put("id", item.getLineId()).put("zoneId", item.getZoneId()).put("variantId", item.getProductId()).put("qty", item.getQty());
        });
        try {
            String encoded = json.writeValueAsString(RenderPreviewScene.canonical(snapshot));
            return new Snapshot(canvas.getRevision(), encoded, VisionImagePreparer.sha256(encoded.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException error) {
            throw new BusinessException("方案快照无法保存");
        }
    }
}
