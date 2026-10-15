package com.rsdp.officeproduct.importer.service.spatial;

import com.rsdp.officeproduct.importer.dto.spatial.SpatialDerived;
import com.rsdp.officeproduct.importer.dto.spatial.SpatialGeometry;
import com.rsdp.officeproduct.importer.dto.spatial.SpatialRectangleFrame;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpatialGeometryServiceTest {

    private final SpatialGeometryService service = new SpatialGeometryService();

    @Test
    void rectangle6x4Area24() {
        SpatialGeometry g = rectangle(0, 0, 6000, 0, 6000, 4000, 0, 4000);
        SpatialDerived d = service.derive(g, List.of());
        assertEquals(0, d.getAreaM2().subtract(new BigDecimal("24.0000")).abs().doubleValue(), 0.0001);
        assertEquals(0, d.getBoundaryAreaM2().subtract(new BigDecimal("24.0000")).abs().doubleValue(), 0.0001);
        assertNotNull(d.getRectangleDimensions());
        assertEquals(6000.0, d.getRectangleDimensions().getWidthMm(), 0.01);
        assertEquals(4000.0, d.getRectangleDimensions().getDepthMm(), 0.01);
    }

    @Test
    void rectangleResize8x4Area32() {
        SpatialGeometry g = rectangle(0, 0, 6000, 0, 6000, 4000, 0, 4000);
        SpatialRectangleFrame frame = service.inferRectangleFrame(g);
        assertNotNull(frame);
        SpatialGeometry resized = service.resizeRectangle(g, frame, 8000, 4000, frame.getCornerVertexIds().getU0v0());
        SpatialDerived d = service.derive(resized, List.of());
        assertEquals(0, d.getAreaM2().subtract(new BigDecimal("32.0000")).abs().doubleValue(), 0.0001);
        assertEquals(8000.0, d.getRectangleDimensions().getWidthMm(), 1.0);
    }

    @Test
    void lShapeArea27() {
        SpatialGeometry g = polygon(List.of(
            List.of(0.0, 0.0),
            List.of(6000.0, 0.0),
            List.of(6000.0, 3000.0),
            List.of(3000.0, 3000.0),
            List.of(3000.0, 6000.0),
            List.of(0.0, 6000.0)
        ));
        SpatialDerived d = service.derive(g, List.of());
        assertEquals(0, d.getBoundaryAreaM2().subtract(new BigDecimal("27.0000")).abs().doubleValue(), 0.0001);
        assertNull(d.getRectangleDimensions());
    }

    @Test
    void moveVertexUpdatesArea() {
        SpatialGeometry g = rectangle(0, 0, 6000, 0, 6000, 4000, 0, 4000);
        String vertexId = g.getOuterVertexIds().get(2);
        SpatialGeometry moved = service.moveVertex(g, vertexId, 8000, 4000);
        SpatialDerived d = service.derive(moved, List.of());
        // single vertex move produces trapezoid: area 28 m² for this case
        assertEquals(0, d.getAreaM2().subtract(new BigDecimal("28.0000")).abs().doubleValue(), 0.0001);
    }

    @Test
    void detectsSelfIntersection() {
        SpatialGeometry g = polygon(List.of(
            List.of(0.0, 0.0),
            List.of(6000.0, 0.0),
            List.of(0.0, 4000.0),
            List.of(6000.0, 4000.0)
        ));
        var issues = service.validate(g);
        assertTrue(issues.stream().anyMatch(i -> "SELF_INTERSECTION".equals(i.getCode())));
    }

    private SpatialGeometry rectangle(double x1, double y1, double x2, double y2, double x3, double y3, double x4, double y4) {
        return polygon(List.of(List.of(x1, y1), List.of(x2, y2), List.of(x3, y3), List.of(x4, y4)));
    }

    private SpatialGeometry polygon(List<List<Double>> outer) {
        SpatialGeometry g = new SpatialGeometry();
        g.setType(SpatialGeometry.TYPE_POLYGON);
        g.setCoordinateSystem(SpatialGeometry.COORDINATE_WORLD_MM);
        g.setOuter(outer);
        g.setOuterVertexIds(service.createVertexIds(outer.size()));
        g.setHoles(List.of());
        g.setHoleVertexIds(List.of());
        return g;
    }
}
