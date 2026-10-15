package com.rsdp.officeproduct.importer.service.spatial;

import com.rsdp.officeproduct.importer.dto.spatial.SpatialGeometry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SpatialCalibrationService {

    /**
     * 两点校准：世界原点取第一个参考点，X 向右、Y 向上。
     * s = referenceLengthMm / 两点像素距离。
     * 矩阵 [s, 0, 0, -s, -s*x1, s*y1]
     */
    public List<Double> computeSourceToWorld(List<List<Double>> referencePoints, double referenceLengthMm) {
        if (referencePoints == null || referencePoints.size() != 2 || referenceLengthMm <= 0) {
            return null;
        }
        List<Double> p1 = referencePoints.get(0);
        List<Double> p2 = referencePoints.get(1);
        double dx = p2.get(0) - p1.get(0);
        double dy = p2.get(1) - p1.get(1);
        double dist = Math.hypot(dx, dy);
        if (dist < 1e-9) return null;
        double s = referenceLengthMm / dist;
        return List.of(s, 0.0, 0.0, -s, -s * p1.get(0), s * p1.get(1));
    }

    public double mmPerSourceUnit(List<Double> sourceToWorld) {
        if (sourceToWorld == null || sourceToWorld.size() < 4) return 0;
        double a = sourceToWorld.get(0);
        double b = sourceToWorld.get(1);
        double c = sourceToWorld.get(2);
        double d = sourceToWorld.get(3);
        return Math.sqrt((a * a + b * b + c * c + d * d) / 2.0);
    }

    public List<Double> transformPoint(List<Double> sourcePoint, List<Double> sourceToWorld) {
        if (sourceToWorld == null || sourceToWorld.size() != 6) return null;
        double x = sourcePoint.get(0);
        double y = sourcePoint.get(1);
        double a = sourceToWorld.get(0);
        double b = sourceToWorld.get(1);
        double c = sourceToWorld.get(2);
        double d = sourceToWorld.get(3);
        double e = sourceToWorld.get(4);
        double f = sourceToWorld.get(5);
        return List.of(a * x + c * y + e, b * x + d * y + f);
    }

    public List<Double> inverseMatrix(List<Double> m) {
        if (m == null || m.size() != 6) return null;
        double a = m.get(0), b = m.get(1), c = m.get(2), d = m.get(3), e = m.get(4), f = m.get(5);
        double det = a * d - b * c;
        if (Math.abs(det) < 1e-12) return null;
        double invA = d / det;
        double invB = -b / det;
        double invC = -c / det;
        double invD = a / det;
        double invE = (c * f - d * e) / det;
        double invF = (b * e - a * f) / det;
        return List.of(invA, invB, invC, invD, invE, invF);
    }

    public List<Double> compose(List<Double> a, List<Double> b) {
        if (a == null || a.size() != 6 || b == null || b.size() != 6) return null;
        double a1 = a.get(0), a2 = a.get(1), a3 = a.get(2), a4 = a.get(3), a5 = a.get(4), a6 = a.get(5);
        double b1 = b.get(0), b2 = b.get(1), b3 = b.get(2), b4 = b.get(3), b5 = b.get(4), b6 = b.get(5);
        return List.of(
            a1 * b1 + a3 * b2,
            a2 * b1 + a4 * b2,
            a1 * b3 + a3 * b4,
            a2 * b3 + a4 * b4,
            a1 * b5 + a3 * b6 + a5,
            a2 * b5 + a4 * b6 + a6
        );
    }

    public SpatialGeometry transformGeometry(SpatialGeometry geometry, List<Double> sourceToWorld) {
        if (geometry == null) return null;
        SpatialGeometry result = copy(geometry);
        result.setCoordinateSystem(SpatialGeometry.COORDINATE_WORLD_MM);
        result.setOuter(transformRing(result.getOuter(), sourceToWorld));
        if (result.getHoles() != null) {
            List<List<List<Double>>> newHoles = new ArrayList<>();
            for (List<List<Double>> hole : result.getHoles()) {
                newHoles.add(transformRing(hole, sourceToWorld));
            }
            result.setHoles(newHoles);
        }
        return result;
    }

    private List<List<Double>> transformRing(List<List<Double>> ring, List<Double> m) {
        List<List<Double>> out = new ArrayList<>();
        for (List<Double> p : ring) {
            out.add(transformPoint(p, m));
        }
        return out;
    }

    private SpatialGeometry copy(SpatialGeometry geometry) {
        SpatialGeometry g = new SpatialGeometry();
        g.setType(geometry.getType());
        g.setCoordinateSystem(geometry.getCoordinateSystem());
        g.setOuter(copyRing(geometry.getOuter()));
        g.setOuterVertexIds(geometry.getOuterVertexIds() == null ? null : new ArrayList<>(geometry.getOuterVertexIds()));
        if (geometry.getHoles() != null) {
            List<List<List<Double>>> holes = new ArrayList<>();
            for (List<List<Double>> hole : geometry.getHoles()) {
                holes.add(copyRing(hole));
            }
            g.setHoles(holes);
            g.setHoleVertexIds(geometry.getHoleVertexIds() == null ? null : new ArrayList<>(geometry.getHoleVertexIds()));
        }
        return g;
    }

    private List<List<Double>> copyRing(List<List<Double>> ring) {
        List<List<Double>> out = new ArrayList<>();
        for (List<Double> p : ring) {
            out.add(new ArrayList<>(p));
        }
        return out;
    }
}
