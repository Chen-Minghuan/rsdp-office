package com.rsdp.officeproduct.importer.service.spatial;

import com.rsdp.officeproduct.importer.dto.spatial.SpatialGeometry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpatialCalibrationServiceTest {

    private final SpatialCalibrationService service = new SpatialCalibrationService();

    @Test
    void twoPointCalibration600pxTo6000mm() {
        List<List<Double>> refs = List.of(List.of(100.0, 500.0), List.of(700.0, 500.0));
        List<Double> matrix = service.computeSourceToWorld(refs, 6000.0);
        assertNotNull(matrix);
        assertEquals(6, matrix.size());
        assertEquals(10.0, matrix.get(0), 0.0001);
        assertEquals(0.0, matrix.get(1), 0.0001);
        assertEquals(0.0, matrix.get(2), 0.0001);
        assertEquals(-10.0, matrix.get(3), 0.0001);
        assertEquals(-1000.0, matrix.get(4), 0.0001);
        assertEquals(5000.0, matrix.get(5), 0.0001);

        List<Double> world = service.transformPoint(List.of(100.0, 500.0), matrix);
        assertEquals(0.0, world.get(0), 0.0001);
        assertEquals(0.0, world.get(1), 0.0001);

        List<Double> world2 = service.transformPoint(List.of(700.0, 500.0), matrix);
        assertEquals(6000.0, world2.get(0), 0.0001);
        assertEquals(0.0, world2.get(1), 0.0001);
    }

    @Test
    void roundTripErrorWithinTolerance() {
        List<List<Double>> refs = List.of(List.of(100.0, 500.0), List.of(700.0, 500.0));
        List<Double> matrix = service.computeSourceToWorld(refs, 6000.0);
        List<Double> inv = service.inverseMatrix(matrix);
        List<Double> source = List.of(250.0, 320.0);
        List<Double> world = service.transformPoint(source, matrix);
        List<Double> back = service.transformPoint(world, inv);
        assertEquals(source.get(0), back.get(0), 0.0001);
        assertEquals(source.get(1), back.get(1), 0.0001);
    }

    @Test
    void transformGeometryFlipsY() {
        SpatialGeometry g = new SpatialGeometry();
        g.setType(SpatialGeometry.TYPE_POLYGON);
        g.setCoordinateSystem(SpatialGeometry.COORDINATE_SOURCE_PAGE);
        g.setOuter(List.of(List.of(100.0, 500.0), List.of(700.0, 500.0), List.of(700.0, 100.0), List.of(100.0, 100.0)));
        g.setOuterVertexIds(List.of("V-0", "V-1", "V-2", "V-3"));
        g.setHoles(List.of());
        g.setHoleVertexIds(List.of());

        List<Double> matrix = service.computeSourceToWorld(List.of(List.of(100.0, 500.0), List.of(700.0, 500.0)), 6000.0);
        SpatialGeometry world = service.transformGeometry(g, matrix);
        assertEquals(SpatialGeometry.COORDINATE_WORLD_MM, world.getCoordinateSystem());
        assertEquals(0.0, world.getOuter().get(0).get(0), 0.0001);
        assertEquals(0.0, world.getOuter().get(0).get(1), 0.0001);
    }
}
