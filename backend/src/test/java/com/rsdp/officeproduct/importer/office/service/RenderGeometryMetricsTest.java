package com.rsdp.officeproduct.importer.office.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RenderGeometryMetricsTest {
    private final List<RenderSpatialControl.Point1000> square=List.of(new RenderSpatialControl.Point1000(0,0),new RenderSpatialControl.Point1000(100,0),new RenderSpatialControl.Point1000(100,100),new RenderSpatialControl.Point1000(0,100));
    @Test void computesIouCenterErrorAndRejectsInvalidPolygons() {
        assertEquals(1,RenderGeometryMetrics.iou(square,square),.0001);
        var shifted=List.of(new RenderSpatialControl.Point1000(50,0),new RenderSpatialControl.Point1000(150,0),new RenderSpatialControl.Point1000(150,100),new RenderSpatialControl.Point1000(50,100));
        assertEquals(1.0/3,RenderGeometryMetrics.iou(square,shifted),.0001);
        assertTrue(Double.isNaN(RenderGeometryMetrics.iou(square,List.of())));
        assertFalse(RenderGeometryMetrics.valid(List.of(new RenderSpatialControl.Point1000(-1,0),new RenderSpatialControl.Point1000(2,0),new RenderSpatialControl.Point1000(2,2))));
        assertEquals(.05,RenderGeometryMetrics.centerError(new RenderSpatialControl.Point1000(0,0),new RenderSpatialControl.Point1000(30,40),1000),.0001);
    }
    @Test void assignmentIsOneToOneForRectangularAndMissingMatrices() {
        assertArrayEquals(new int[]{1,0},RenderGeometryMetrics.assign(new double[][]{{9,0},{0,9}}));
        assertArrayEquals(new int[]{1,0,-1},RenderGeometryMetrics.assign(new double[][]{{9,0},{0,9},{8,8}}));
        int[] wider=RenderGeometryMetrics.assign(new double[][]{{9,0,8},{0,9,8}});
        assertEquals(2,wider.length);assertTrue(wider[0]==1&&wider[1]==0||wider[0]==1&&wider[1]==2||wider[0]==2&&wider[1]==0);
        assertArrayEquals(new int[]{-1,-1},RenderGeometryMetrics.assign(new double[2][0]));
    }
}
