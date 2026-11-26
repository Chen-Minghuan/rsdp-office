package com.rsdp.officeproduct.importer.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProductFilterSupportTest {
    @Test
    void derivesApprovedTaxonomyWithoutGuessing() {
        var source = new ProductFilterSupport.Source("B", "1.4米双人位", "蜜芽胡桃+雅士白+灯带", "", List.of());
        var row = ProductFilterSupport.attributes(source);
        assertEquals("UNCLASSIFIED", row.productType());
        assertEquals("UNSPECIFIED", row.spaceType());
        assertEquals(Set.of("蜜芽胡桃", "雅士白"), row.colorNames());
        assertEquals("UNSPECIFIED", row.storageType());
    }

    @Test
    void appliesStoragePriorityAndMultiLabels() {
        var source = new ProductFilterSupport.Source("B", "1.6米主管台", "纳柯", "支撑柜带抽屉，钢脚", List.of());
        var row = ProductFilterSupport.attributes(source);
        assertEquals("MANAGER", row.spaceType());
        assertEquals("DESK", row.productType());
        assertEquals("SUPPORT_CABINET", row.storageType());
        assertEquals(Set.of("STEEL", "SUPPORT_CABINET"), row.legTypes());
    }

    @Test
    void requiresMaterialAndWidthOnTheSameVariant() {
        var variants = List.of(
            new ProductFilterSupport.Source.Variant("PB", new BigDecimal("1200")),
            new ProductFilterSupport.Source.Variant("WO", new BigDecimal("1400")));
        var row = new ProductFilterSupport.Attributes("B", "STAFF", "DESK", Set.of(), Set.of(), "UNSPECIFIED",
            Set.of("R1000_1200", "R1200_1600"), variants);
        var matching = new ProductFilter(List.of(), List.of(), List.of(), List.of("PB"), List.of(), List.of("R1000_1200"), List.of(), List.of());
        var wrongPair = new ProductFilter(List.of(), List.of(), List.of(), List.of("PB"), List.of(), List.of("R1200_1600"), List.of(), List.of());
        assertTrue(row.matches(matching));
        assertFalse(row.matches(wrongPair));
    }

    @Test
    void classifiesWorkstationsOnlyWithAnExplicitDeskKeyword() {
        assertEquals("DESK", ProductFilterSupport.attributes(
            new ProductFilterSupport.Source("B", "单人位职员台", "", "", List.of())).productType());
        assertEquals("UNCLASSIFIED", ProductFilterSupport.attributes(
            new ProductFilterSupport.Source("B", "1.4米双人位", "", "", List.of())).productType());
    }

    @Test
    void prefersTheSpecificLongSideCabinetOverGenericSideCabinet() {
        var row = ProductFilterSupport.attributes(
            new ProductFilterSupport.Source("B", "2.4米高管台", "", "长副柜，副柜", List.of()));
        assertEquals("LONG_SIDE_CABINET", row.storageType());
    }

    @Test
    void rejectsAWidthFilterWhenTheVariantWidthIsUnknown() {
        var row = new ProductFilterSupport.Attributes("B", "STAFF", "DESK", Set.of(), Set.of(), "UNSPECIFIED",
            Set.of("UNSPECIFIED"), List.of(new ProductFilterSupport.Source.Variant("PB", null)));
        var widthOnly = new ProductFilter(List.of(), List.of(), List.of(), List.of(), List.of(), List.of("R1000_1200"), List.of(), List.of());
        var unspecified = new ProductFilter(List.of(), List.of(), List.of(), List.of(), List.of(), List.of("UNSPECIFIED"), List.of(), List.of());
        var noFilters = new ProductFilter(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        assertFalse(row.matches(widthOnly));
        assertTrue(row.matches(unspecified));
        assertTrue(row.matches(noFilters));
    }

    @Test
    void assignsWidthRangesWithUpperInclusiveBoundaries() {
        assertEquals("LE_1000", ProductFilterSupport.widthRangeCode(new BigDecimal("400")));
        assertEquals("LE_1000", ProductFilterSupport.widthRangeCode(new BigDecimal("1000")));
        assertEquals("R1000_1200", ProductFilterSupport.widthRangeCode(new BigDecimal("1001")));
        assertEquals("R1000_1200", ProductFilterSupport.widthRangeCode(new BigDecimal("1200")));
        assertEquals("R1200_1600", ProductFilterSupport.widthRangeCode(new BigDecimal("1201")));
        assertEquals("R1200_1600", ProductFilterSupport.widthRangeCode(new BigDecimal("1600")));
        assertEquals("R1600_2000", ProductFilterSupport.widthRangeCode(new BigDecimal("1800")));
        assertEquals("R2000_2400", ProductFilterSupport.widthRangeCode(new BigDecimal("2312")));
        assertEquals("R2400_2800", ProductFilterSupport.widthRangeCode(new BigDecimal("2800")));
        assertEquals("GT_2800", ProductFilterSupport.widthRangeCode(new BigDecimal("2801")));
        assertEquals("GT_2800", ProductFilterSupport.widthRangeCode(new BigDecimal("4200")));
        assertEquals("UNSPECIFIED", ProductFilterSupport.widthRangeCode(null));
        var row = ProductFilterSupport.attributes(new ProductFilterSupport.Source("B", "1.4米职员台", "", "",
            List.of(new ProductFilterSupport.Source.Variant("PB", new BigDecimal("1400")))));
        assertEquals(Set.of("R1200_1600"), row.widthRanges());
        var noWidth = ProductFilterSupport.attributes(new ProductFilterSupport.Source("B", "职员台", "", "", List.of()));
        assertEquals(Set.of("UNSPECIFIED"), noWidth.widthRanges());
    }
}
