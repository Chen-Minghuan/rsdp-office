package com.rsdp.officeproduct.importer.office.product;

/** Keeps local product IDs distinct from RSDP IDs in collections and bundles. */
public final class LocalProductReference {
    public static final String PREFIX = "OFFICE-LOCAL-";

    private LocalProductReference() {}

    public static boolean isLocal(String reference) {
        return reference != null && reference.startsWith(PREFIX);
    }

    public static String productId(String reference) {
        return reference.substring(PREFIX.length());
    }

    public static String imagePath(String imageId) {
        return "/api/v1/office/local-products/images/" + imageId;
    }
}
