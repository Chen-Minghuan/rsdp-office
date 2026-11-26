package com.rsdp.officeproduct.importer.office.dto;

import java.util.List;

public final class OfficeProductBundleDtos {
    private OfficeProductBundleDtos() {}

    public record Item(String rspuId, String variantId, int quantity) {}
    public record SavedItem(String rspuId, String variantId, int quantity, String name, String variant, String imageUrl) {}
    public record Bundle(String bundleId, String name, int revision, List<SavedItem> items) {}
    public record Save(String name, int revision, List<Item> items) {}
    public record Apply(String zoneId, Integer revision, String schemeToken, boolean automatic, List<Item> items) {}
}
