package com.rsdp.officeproduct.importer.office.dto;

import java.util.List;

public final class OfficeSchemeDtos {
    private OfficeSchemeDtos() {}

    public record Change(String zoneId, Integer revision, String schemeToken, String targetSlot) {}
    public record State(String activeSlot, boolean alternativeSaved) {}
    public record Result(CanvasDto canvas, List<CartItemDto> cart, State scheme) {}
}
