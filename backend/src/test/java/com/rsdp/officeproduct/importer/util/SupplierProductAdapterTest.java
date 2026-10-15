package com.rsdp.officeproduct.importer.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SupplierProductAdapterTest {
    @Test
    void exactThreePartSizeUsesMillimetres() {
        Map<String, String> fields = new HashMap<>();
        SupplierProductAdapter.fillDimensions("3200×2050×750", fields);
        assertEquals("3200", fields.get("widthMm"));
        assertEquals("2050", fields.get("depthMm"));
        assertEquals("750", fields.get("heightMm"));
        assertArrayEquals(new int[]{3200, 2050, 750}, SupplierProductAdapter.exactDimensions("3200×2050×750"));
    }

    @Test
    void ambiguousSizeDoesNotInventAWidthOrHeight() {
        Map<String, String> widthChoice = new HashMap<>();
        SupplierProductAdapter.fillDimensions("1600/1800x800x750", widthChoice);
        assertFalse(widthChoice.containsKey("widthMm"));
        assertFalse(widthChoice.containsKey("depthMm"));
        Map<String, String> heightChoice = new HashMap<>();
        SupplierProductAdapter.fillDimensions("1600x800x750/1100", heightChoice);
        assertEquals("1600", heightChoice.get("widthMm"));
        assertEquals("800", heightChoice.get("depthMm"));
        assertFalse(heightChoice.containsKey("heightMm"));
        assertNull(SupplierProductAdapter.exactDimensions("1600x800x750/1100"));
        assertTrue(SupplierProductAdapter.widthAlternatives("1600x800x750/1100").isEmpty());
    }

    @Test
    void twoExplicitWidthsProduceTwoExactMillimetreSizes() {
        assertEquals(List.of("2400x1800x750", "2200x1800x750"),
            SupplierProductAdapter.widthAlternatives("2400/2200x1800\nx750"));
        assertTrue(SupplierProductAdapter.widthAlternatives("2400/2200x1800x750/800").isEmpty());
    }

}
