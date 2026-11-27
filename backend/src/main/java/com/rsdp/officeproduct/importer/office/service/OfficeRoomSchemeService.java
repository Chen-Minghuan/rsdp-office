package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.dto.CanvasDto;
import com.rsdp.officeproduct.importer.office.dto.CartItemDto;
import com.rsdp.officeproduct.importer.office.dto.OfficeSchemeDtos;
import com.rsdp.officeproduct.importer.office.dto.SaveCanvasRequest;
import com.rsdp.officeproduct.importer.office.entity.OfficeCart;
import com.rsdp.officeproduct.importer.office.mapper.OfficeCartMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeRoomSchemeMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeWorkspaceLockMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class OfficeRoomSchemeService {
    private final OfficeService office;
    private final OfficeWorkspaceLockMapper locks;
    private final OfficeRoomSchemeMapper schemes;
    private final OfficeCartMapper carts;
    private final ObjectMapper json;

    private record Room(List<JsonNode> objects, JsonNode zone, List<JsonNode> furniture) {}
    private record Slot(JsonNode geometry, List<JsonNode> furniture, List<CartItemDto> cart) {}

    public OfficeSchemeDtos.State state(String floorId, String zoneId) {
        room(office.getCanvas(floorId), zoneId);
        return state(schemes.find(floorId, zoneId));
    }

    @Transactional
    public OfficeSchemeDtos.Result activate(String floorId, OfficeSchemeDtos.Change change) {
        if (change == null || (!"A".equals(change.targetSlot()) && !"B".equals(change.targetSlot()))) {
            throw new BusinessException("请选择 Plan A 或 Plan B");
        }
        CanvasDto canvas = lockCurrent(floorId, change);
        Room room = room(canvas, change.zoneId());
        var saved = schemes.find(floorId, change.zoneId());
        var currentState = state(saved);
        var allCart = office.listCart(floorId);
        if (currentState.activeSlot().equals(change.targetSlot())) return new OfficeSchemeDtos.Result(canvas, allCart, currentState);
        if (saved != null && saved.confirmedSlot() != null) {
            throw new BusinessException("当前空间已确认使用 Plan " + saved.activeSlot() + "，请先取消确认");
        }

        JsonNode geometry = OfficeSpaceReviewSupport.snapshot(json, room.zone());
        Slot outgoing = new Slot(geometry, room.furniture(),
            allCart.stream().filter(line -> change.zoneId().equals(line.getZoneId())).toList());
        Slot incoming = saved == null ? outgoing : decode(saved.inactiveJson());
        ArrayNode next = json.createArrayNode();
        var occupied = new HashSet<String>();
        for (JsonNode item : room.objects()) {
            if (!furnitureIn(item, change.zoneId())) {
                next.add(item);
                occupied.add(item.path("id").asText());
            }
        }
        boolean geometryChanged = !OfficeSpaceReviewSupport.jsonEquivalent(geometry, incoming.geometry());
        for (JsonNode item : incoming.furniture()) {
            if (!(item instanceof ObjectNode furniture) || !furnitureIn(furniture, change.zoneId())
                || !occupied.add(furniture.path("id").asText())) throw new BusinessException("方案家具记录冲突");
            ObjectNode copy = furniture.deepCopy();
            if (geometryChanged) copy.put("layoutConfirmed", false);
            next.add(copy);
        }
        clearRoomCart(floorId, change.zoneId());
        for (CartItemDto line : incoming.cart()) restoreCart(floorId, change.zoneId(), room.zone().path("name").asText(), line);
        CanvasDto updated = persist(canvas, next);
        schemes.save(floorId, change.zoneId(), change.targetSlot(), encode(outgoing));
        return new OfficeSchemeDtos.Result(updated, office.listCart(floorId), new OfficeSchemeDtos.State(change.targetSlot(), true, null));
    }

    @Transactional
    public OfficeSchemeDtos.Result clearSelection(String floorId, OfficeSchemeDtos.Change change) {
        CanvasDto canvas = lockCurrent(floorId, change);
        Room room = room(canvas, change.zoneId());
        for (JsonNode item : room.furniture()) {
            ObjectNode furniture = (ObjectNode) item;
            furniture.remove(List.of("productId", "productSelection"));
        }
        clearRoomCart(floorId, change.zoneId());
        CanvasDto updated = persist(canvas, room.objects());
        return new OfficeSchemeDtos.Result(updated, office.listCart(floorId), state(schemes.find(floorId, change.zoneId())));
    }

    /**
     * 确认使用当前激活的方案（Plan A 或 Plan B）：确认后 activate 被拒绝，须先取消确认。
     * 不改变画布内容，仅锁定选择，因此无需校验画布版本。
     */
    @Transactional
    public OfficeSchemeDtos.State confirmChoice(String floorId, OfficeSchemeDtos.Change change) {
        if (change == null || change.zoneId() == null) throw new BusinessException("缺少当前房间");
        locks.lockWorkspace(floorId);
        room(office.getCanvas(floorId), change.zoneId());
        var saved = schemes.find(floorId, change.zoneId());
        if (saved == null) throw new BusinessException("当前空间还没有可确认的方案");
        if (saved.confirmedSlot() != null) return state(saved);
        schemes.setConfirmedSlot(floorId, change.zoneId(), saved.activeSlot());
        return new OfficeSchemeDtos.State(saved.activeSlot(), true, saved.activeSlot());
    }

    /** 取消方案选择确认，恢复 Plan A/B 切换。 */
    @Transactional
    public OfficeSchemeDtos.State cancelChoice(String floorId, OfficeSchemeDtos.Change change) {
        if (change == null || change.zoneId() == null) throw new BusinessException("缺少当前房间");
        locks.lockWorkspace(floorId);
        var saved = schemes.find(floorId, change.zoneId());
        if (saved == null) throw new BusinessException("当前空间还没有方案记录");
        if (saved.confirmedSlot() == null) return state(saved);
        schemes.setConfirmedSlot(floorId, change.zoneId(), null);
        return new OfficeSchemeDtos.State(saved.activeSlot(), true, null);
    }

    /** 方案对比：激活槽家具取自画布，另一槽家具取自 inactive_json（无方案行时为空）。 */
    public OfficeSchemeDtos.Slots slots(String floorId, String zoneId) {
        CanvasDto canvas = office.getCanvas(floorId);
        Room room = room(canvas, zoneId);
        var saved = schemes.find(floorId, zoneId);
        String activeSlot = saved == null ? "A" : saved.activeSlot();
        List<OfficeSchemeDtos.SlotFurniture> inactive = saved == null ? List.of()
            : decode(saved.inactiveJson()).furniture().stream().map(OfficeRoomSchemeService::summarize).toList();
        return new OfficeSchemeDtos.Slots(activeSlot,
            room.furniture().stream().map(OfficeRoomSchemeService::summarize).toList(), inactive);
    }

    private static OfficeSchemeDtos.SlotFurniture summarize(JsonNode item) {
        String label = item.path("label").isTextual() ? item.path("label").asText() : null;
        JsonNode productName = item.path("productSelection").path("product").path("name");
        return new OfficeSchemeDtos.SlotFurniture(item.path("id").asText(), label,
            productName.isTextual() ? productName.asText() : null, item.path("w").asInt(), item.path("h").asInt());
    }

    private CanvasDto lockCurrent(String floorId, OfficeSchemeDtos.Change change) {
        if (change == null || change.zoneId() == null || change.revision() == null) throw new BusinessException("缺少当前房间或画布版本");
        locks.lockWorkspace(floorId);
        CanvasDto canvas = office.getCanvas(floorId);
        if (canvas == null || !Objects.equals(canvas.getRevision(), change.revision())
            || !Objects.equals(canvas.getSchemeToken(), change.schemeToken())) {
            throw new BusinessException("房间方案已变化，请刷新画布后重试");
        }
        JsonNode objects = json.valueToTree(canvas.getObjects());
        OfficeCanvasReviewPolicy.requireLayoutReady(objects, change.zoneId());
        OfficeCanvasReviewPolicy.requireSpacesReviewConfirmed(objects);
        return canvas;
    }

    private Room room(CanvasDto canvas, String zoneId) {
        if (canvas == null || canvas.getObjects() == null || zoneId == null || zoneId.isBlank()) throw new BusinessException("请选择已保存的空间");
        JsonNode array = json.valueToTree(canvas.getObjects());
        List<JsonNode> objects = new ArrayList<>();
        array.forEach(objects::add);
        JsonNode zone = objects.stream().filter(item -> "zone".equals(item.path("kind").asText()) && zoneId.equals(item.path("id").asText()))
            .findFirst().orElseThrow(() -> new BusinessException("当前楼层不存在该空间"));
        return new Room(objects, zone, objects.stream().filter(item -> furnitureIn(item, zoneId)).toList());
    }

    private boolean furnitureIn(JsonNode item, String zoneId) {
        return "furniture".equals(item.path("kind").asText()) && zoneId.equals(item.path("zoneId").asText());
    }

    private OfficeSchemeDtos.State state(OfficeRoomSchemeMapper.Row saved) {
        return saved == null ? new OfficeSchemeDtos.State("A", false, null)
            : new OfficeSchemeDtos.State(saved.activeSlot(), true, saved.confirmedSlot());
    }

    private void clearRoomCart(String floorId, String zoneId) {
        carts.delete(new QueryWrapper<OfficeCart>().eq("project_id", floorId).eq("zone_id", zoneId));
    }

    private void restoreCart(String floorId, String zoneId, String zoneName, CartItemDto saved) {
        OfficeCart line = new OfficeCart();
        line.setLineId(saved.getLineId());
        line.setProjectId(floorId);
        line.setZoneId(zoneId);
        line.setZoneName(zoneName);
        line.setProductId(saved.getProductId());
        line.setQty(saved.getQty());
        line.setCreatedAt(LocalDateTime.now());
        line.setUpdatedAt(line.getCreatedAt());
        carts.insert(line);
    }

    private CanvasDto persist(CanvasDto canvas, Iterable<JsonNode> items) {
        SaveCanvasRequest save = new SaveCanvasRequest();
        save.setProjectId(canvas.getProjectId());
        save.setRevision(canvas.getRevision() + 1);
        save.setSchemeToken(canvas.getSchemeToken());
        save.setMode(canvas.getMode());
        save.setDesignZoneId(canvas.getDesignZoneId());
        save.setFiles(canvas.getFiles());
        save.setBlank(canvas.getBlank());
        List<Object> objects = new ArrayList<>();
        items.forEach(objects::add);
        save.setObjects(objects);
        return office.replaceSchemeCanvas(save);
    }

    private String encode(Slot slot) {
        try { return json.writeValueAsString(slot); }
        catch (JsonProcessingException error) { throw new BusinessException("无法保存房间方案"); }
    }

    private Slot decode(String value) {
        try { return json.readValue(value, Slot.class); }
        catch (JsonProcessingException error) { throw new BusinessException("无法读取已保存的房间方案"); }
    }
}
