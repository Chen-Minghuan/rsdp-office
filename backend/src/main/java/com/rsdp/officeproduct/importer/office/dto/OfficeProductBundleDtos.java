package com.rsdp.officeproduct.importer.office.dto;

import java.util.List;

public final class OfficeProductBundleDtos {
    private OfficeProductBundleDtos() {}

    public record Item(String rspuId, String variantId, int quantity) {}
    /** available 按当前目录与工作区重新核验；旧 JSON 未含此字段也会在读取时重新解析。 */
    public record SavedItem(String rspuId, String variantId, int quantity, String name, String variant, String imageUrl, boolean available) {
        public SavedItem(String rspuId, String variantId, int quantity, String name, String variant, String imageUrl) {
            this(rspuId, variantId, quantity, name, variant, imageUrl, true);
        }
    }
    public record Bundle(String bundleId, String name, int revision, List<SavedItem> items) {}
    public record Save(String name, int revision, List<Item> items) {}
    public record Apply(String zoneId, Integer revision, String schemeToken, boolean automatic, List<Item> items) {}
}
