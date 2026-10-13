package com.rsdp.officeproduct.importer.util;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class IdGenerator {

    private static final AtomicLong SEQUENCE = new AtomicLong(0);

    private IdGenerator() {
    }

    public static String rspuId() {
        return "RSPU-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    public static String variantId(String rspuId, long seq) {
        return String.format("%s-V%03d", rspuId, seq);
    }

    public static String imageId() {
        return "IMG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
