package com.rsdp.officeproduct.importer.office.product;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class LocalProductModels {
    public static final int MAX_PRODUCTS = 100;
    public static final int MAX_VARIANTS = 30;
    public static final int MAX_IMAGES = 6;
    public static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_BATCH_IMAGE_BYTES = 12 * 1024 * 1024;

    private LocalProductModels() {}

    public record Variant(String id, String name, BigDecimal widthMm, BigDecimal depthMm, BigDecimal heightMm,
                          String sizeText, String color, String material,
                          @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal referencePrice) {
        Variant withReferencePrice(BigDecimal price) {
            return new Variant(id, name, widthMm, depthMm, heightMm, sizeText, color, material, price);
        }
    }
    public record ImageInput(String id, String mediaType, byte[] content) {}
    public record ImageRef(String id, String mediaType) {}
    public record Input(String id, Integer revision, String name, String categoryCode, String factoryCode,
                        String factoryModel, List<Variant> variants, List<ImageInput> images) {}
    public record SaveRequest(UUID batchId, List<Input> products, UUID previewId) {
        SaveRequest(UUID batchId, List<Input> products) { this(batchId, products, null); }
    }
    public record Saved(String id, int revision, String name) {}
    public record Summary(String id, int revision, String name, String categoryCode, String factoryCode, String factoryModel) {}
    public record CatalogItem(String id, int revision, String name, String categoryCode, String factoryCode,
                              String factoryModel, String primaryImageId, BigDecimal minReferencePrice, boolean multipleVariants) {}
    public record Detail(Summary product, List<Variant> variants, List<ImageRef> images, boolean canWrite) {}
    public record Page(boolean canWrite, List<CatalogItem> products, boolean hasMore) {}
    public record Capacity(long used, int limit) {}

    record Dimensions(BigDecimal w, BigDecimal d, BigDecimal h, String unit) {}
    record SubmittedVariant(String id, String name, Dimensions dimensions, String sizeText, String color, String material) {}
    record ProductSnapshot(String id, int revision, String name, String categoryCode, String factoryCode,
                           String factoryModel, List<SubmittedVariant> variants, List<ImageInput> images) {}
    record Delivery(int schemaVersion, UUID batchId, String workspaceId, String actorId, String actorName,
                    List<ProductSnapshot> products) {}
}
