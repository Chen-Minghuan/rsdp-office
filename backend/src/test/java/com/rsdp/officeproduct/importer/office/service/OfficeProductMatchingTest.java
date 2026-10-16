package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class OfficeProductMatchingTest {
    private final ObjectMapper json = new ObjectMapper();
    private BigDecimal n(String value) { return new BigDecimal(value); }
    private OfficeProductDto sku(String width, String depth) {
        var sku = new OfficeProductDto(); sku.setWidthMm(n(width)); sku.setDepthMm(n(depth)); return sku;
    }
    @Test void usesBothAxesAndNeverRoundsAnOversizeProductDown() {
        assertNotNull(OfficeProductMatching.match(sku("1200", "600"), n("1200"), n("600"), false));
        assertNotNull(OfficeProductMatching.match(sku("1199.9", "599.9"), n("1200"), n("600"), false));
        assertNull(OfficeProductMatching.match(sku("1200.01", "600"), n("1200"), n("600"), true));
        assertNull(OfficeProductMatching.match(sku("1400", "400"), n("1200"), n("600"), true));
        assertNull(OfficeProductMatching.match(sku("800", "601"), n("1200"), n("600"), false));
    }
    @Test void rotationMustBeExplicitAndReportsRemainingWidthAndDepth() {
        assertNull(OfficeProductMatching.match(sku("500", "1100"), n("1200"), n("600"), false));
        var match = OfficeProductMatching.match(sku("500", "1100"), n("1200"), n("600"), true);
        assertEquals(90, match.getRotationDeg()); assertEquals(n("100"), match.getRemainingWidthMm()); assertEquals(n("100"), match.getRemainingDepthMm());
    }
    @Test void tiersUseTheSmallerAxisRatioAndDoNotExcludeTinyFits() {
        assertEquals("CLOSE", OfficeProductMatching.match(sku("1020", "510"), n("1200"), n("600"), false).getTier());
        assertEquals("RELAXED", OfficeProductMatching.match(sku("1200", "509.9"), n("1200"), n("600"), false).getTier());
        assertEquals("RELAXED", OfficeProductMatching.match(sku("720", "360"), n("1200"), n("600"), false).getTier());
        assertEquals("SMALL", OfficeProductMatching.match(sku("1200", "359.9"), n("1200"), n("600"), false).getTier());
        assertEquals("SMALL", OfficeProductMatching.match(sku("1", "1"), n("1200"), n("600"), false).getTier());
    }
    @Test void readsExactMmAndExplicitLegacyCmButRejectsUnknownOrConflictingUnits() throws Exception {
        var exact = OfficeProductMatching.dimensions(json.readTree("{\"widthMm\":1200.01,\"depthMm\":600,\"unit\":\"mm\"}"));
        assertEquals(n("1200.01"), exact[0]);
        var legacy = OfficeProductMatching.dimensions(json.readTree("{\"widthCm\":119.99,\"depthCm\":60}"));
        assertEquals(n("1199.90"), legacy[0]); assertEquals(n("600"), legacy[1]);
        assertNull(OfficeProductMatching.dimensions(json.readTree("{\"widthMm\":1200,\"depthMm\":600,\"unit\":\"m\"}"))[0]);
        assertNull(OfficeProductMatching.dimensions(json.readTree("{\"widthMm\":1200,\"depthMm\":600,\"unit\":\"cm\"}"))[0]);
        assertNull(OfficeProductMatching.positive(json.readTree("\"NaN\"")));
        assertNull(OfficeProductMatching.positive(json.readTree("-1")));
        assertNull(OfficeProductMatching.match(new OfficeProductDto(), n("1200"), n("600"), true));
    }
}
