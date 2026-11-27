package com.rsdp.officeproduct.importer.office.dto;

import java.util.List;

public final class OfficeSchemeDtos {
    private OfficeSchemeDtos() {}

    public record Change(String zoneId, Integer revision, String schemeToken, String targetSlot) {}
    /** confirmedSlot 为 null 表示尚未确认使用哪个方案；确认后只能等于 activeSlot（确认期间 activate 被拒绝）。 */
    public record State(String activeSlot, boolean alternativeSaved, String confirmedSlot) {}
    public record Result(CanvasDto canvas, List<CartItemDto> cart, State scheme) {}
    /** 方案对比用的家具摘要：label/productName 可空，w/h 为画布毫米尺寸。 */
    public record SlotFurniture(String id, String label, String productName, int w, int h) {}
    /** active 为当前激活槽（画布）家具，inactive 为另一槽（inactive_json）家具；无方案行时 inactive 为空。 */
    public record Slots(String activeSlot, List<SlotFurniture> active, List<SlotFurniture> inactive) {}
}
