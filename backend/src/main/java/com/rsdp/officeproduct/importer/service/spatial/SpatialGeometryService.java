package com.rsdp.officeproduct.importer.service.spatial;

import com.rsdp.officeproduct.importer.dto.spatial.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@Service
public class SpatialGeometryService {

    public static final String POLICY_VERSION = "GEOM-P1-1";

    private static final double EPS_MM = 0.01;
    private static final double MIN_EDGE_MM = 1.0;
    private static final double MIN_AREA_MM2 = 1.0;

    public SpatialDerived derive(SpatialGeometry geometry, List<SpatialObstacle> obstacles) {
        SpatialDerived derived = new SpatialDerived();
        derived.setBoundsMm(bounds(geometry));

        if (geometry == null || !SpatialGeometry.COORDINATE_WORLD_MM.equals(geometry.getCoordinateSystem())) {
            derived.setBoundaryAreaM2(null);
            derived.setAreaM2(null);
            derived.setRectangleDimensions(null);
            return derived;
        }

        double outerArea = ringArea(geometry.getOuter());
        double holeArea = 0.0;
        if (geometry.getHoles() != null) {
            for (List<List<Double>> hole : geometry.getHoles()) {
                holeArea += Math.abs(ringArea(hole));
            }
        }
        double boundaryAreaMm2 = Math.abs(outerArea) - holeArea;
        derived.setBoundaryAreaM2(toM2(boundaryAreaMm2));

        double obstacleArea = 0.0;
        if (obstacles != null) {
            Set<String> processed = new HashSet<>();
            for (SpatialObstacle o : obstacles) {
                if (o.getExcludesFloorArea() == null || !o.getExcludesFloorArea()) continue;
                if (o.getGeometry() == null || !SpatialGeometry.COORDINATE_WORLD_MM.equals(o.getGeometry().getCoordinateSystem())) continue;
                double oa = Math.abs(ringArea(o.getGeometry().getOuter()));
                // simple cap to boundary to avoid double counting; full union is future work
                if (processed.add(o.getObstacleId())) {
                    obstacleArea += Math.min(oa, boundaryAreaMm2);
                }
            }
        }
        derived.setAreaM2(toM2(Math.max(0.0, boundaryAreaMm2 - obstacleArea)));

        SpatialRectangleFrame frame = inferRectangleFrame(geometry);
        if (frame != null) {
            SpatialDerived.RectangleDimensions dims = new SpatialDerived.RectangleDimensions();
            dims.setWidthMm(rectangleWidth(geometry, frame));
            dims.setDepthMm(rectangleDepth(geometry, frame));
            derived.setRectangleDimensions(dims);
        } else {
            derived.setRectangleDimensions(null);
        }
        return derived;
    }

    public SpatialBounds bounds(SpatialGeometry geometry) {
        if (geometry == null || geometry.getOuter() == null || geometry.getOuter().isEmpty()) return null;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (List<Double> p : geometry.getOuter()) {
            minX = Math.min(minX, p.get(0));
            minY = Math.min(minY, p.get(1));
            maxX = Math.max(maxX, p.get(0));
            maxY = Math.max(maxY, p.get(1));
        }
        SpatialBounds b = new SpatialBounds();
        b.setMinX(minX);
        b.setMinY(minY);
        b.setMaxX(maxX);
        b.setMaxY(maxY);
        return b;
    }

    public double ringArea(List<List<Double>> ring) {
        double sum = 0.0;
        int n = ring.size();
        for (int i = 0; i < n; i++) {
            List<Double> a = ring.get(i);
            List<Double> b = ring.get((i + 1) % n);
            sum += a.get(0) * b.get(1) - b.get(0) * a.get(1);
        }
        return sum / 2.0;
    }

    public BigDecimal toM2(double mm2) {
        if (mm2 < 0) mm2 = 0;
        return BigDecimal.valueOf(mm2 / 1_000_000.0).setScale(4, RoundingMode.HALF_UP);
    }

    public SpatialRectangleFrame inferRectangleFrame(SpatialGeometry geometry) {
        if (geometry == null || geometry.getOuter() == null || geometry.getOuter().size() != 4) return null;
        List<List<Double>> pts = geometry.getOuter();
        List<String> ids = geometry.getOuterVertexIds();
        if (ids == null || ids.size() != 4) return null;

        double[] edgeDirs = new double[4];
        double[] lengths = new double[4];
        for (int i = 0; i < 4; i++) {
            List<Double> a = pts.get(i);
            List<Double> b = pts.get((i + 1) % 4);
            double dx = b.get(0) - a.get(0);
            double dy = b.get(1) - a.get(1);
            lengths[i] = Math.hypot(dx, dy);
            if (lengths[i] < MIN_EDGE_MM) return null;
            edgeDirs[i] = Math.atan2(dy, dx);
        }
        // opposite edges parallel, adjacent perpendicular
        if (!parallel(edgeDirs[0], edgeDirs[2]) || !parallel(edgeDirs[1], edgeDirs[3])) return null;
        if (!perpendicular(edgeDirs[0], edgeDirs[1])) return null;
        if (Math.abs(lengths[0] - lengths[2]) > EPS_MM || Math.abs(lengths[1] - lengths[3]) > EPS_MM) return null;

        // U axis = direction closest to world X, [-45,45]
        double uDir = normalizeAngle(edgeDirs[0]);
        double alt = normalizeAngle(edgeDirs[1]);
        if (Math.abs(alt) < Math.abs(uDir)) uDir = alt;
        double theta = uDir;

        SpatialRectangleFrame frame = new SpatialRectangleFrame();
        frame.setAngleDeg(Math.toDegrees(theta));
        frame.setCornerVertexIds(new SpatialRectangleFrame.RectangleCornerVertexIds());

        // project onto U/V, assign corner ids
        double[] u = new double[4];
        double[] v = new double[4];
        double cu = Math.cos(theta);
        double su = Math.sin(theta);
        for (int i = 0; i < 4; i++) {
            double x = pts.get(i).get(0);
            double y = pts.get(i).get(1);
            u[i] = x * cu + y * su;
            v[i] = -x * su + y * cu;
        }
        // robust assignment: u0v0=min(u)+min(v), u1v0=max(u)+min(v), etc.
        int i00 = argMinSum(u, v, 1, 1);
        int i10 = argMinSum(u, v, -1, 1);
        int i11 = argMinSum(u, v, -1, -1);
        int i01 = argMinSum(u, v, 1, -1);

        frame.getCornerVertexIds().setU0v0(ids.get(i00));
        frame.getCornerVertexIds().setU1v0(ids.get(i10));
        frame.getCornerVertexIds().setU1v1(ids.get(i11));
        frame.getCornerVertexIds().setU0v1(ids.get(i01));
        return frame;
    }

    public SpatialGeometry resizeRectangle(SpatialGeometry geometry, SpatialRectangleFrame frame,
                                            double widthMm, double depthMm, String anchorCornerId) {
        if (geometry == null || geometry.getOuter() == null || geometry.getOuter().size() != 4) return geometry;
        if (frame == null || frame.getCornerVertexIds() == null) return geometry;

        List<List<Double>> pts = new ArrayList<>();
        for (List<Double> p : geometry.getOuter()) pts.add(new ArrayList<>(p));
        List<String> ids = new ArrayList<>(geometry.getOuterVertexIds());

        Map<String, Integer> indexById = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) indexById.put(ids.get(i), i);

        SpatialRectangleFrame.RectangleCornerVertexIds c = frame.getCornerVertexIds();
        Integer ia = indexById.get(c.getU0v0());
        Integer ib = indexById.get(c.getU1v0());
        Integer ic = indexById.get(c.getU1v1());
        Integer id = indexById.get(c.getU0v1());
        if (ia == null || ib == null || ic == null || id == null) return geometry;

        double theta = Math.toRadians(frame.getAngleDeg());
        double cu = Math.cos(theta);
        double su = Math.sin(theta);

        // anchor default u0v0
        String anchorId = (anchorCornerId != null && indexById.containsKey(anchorCornerId)) ? anchorCornerId : c.getU0v0();
        int anchorIdx = indexById.get(anchorId);
        double ax = pts.get(anchorIdx).get(0);
        double ay = pts.get(anchorIdx).get(1);

        // determine anchor's u/v sign relative to u0v0
        int anchorRole = anchorIdx == ia ? 0 : (anchorIdx == ib ? 1 : (anchorIdx == ic ? 2 : 3));
        boolean uMax = (anchorRole == 1 || anchorRole == 2);
        boolean vMax = (anchorRole == 2 || anchorRole == 3);

        double uBase = ax * cu + ay * su;
        double vBase = -ax * su + ay * cu;
        double uMin = uMax ? uBase - widthMm : uBase;
        double uMaxVal = uMax ? uBase : uBase + widthMm;
        double vMin = vMax ? vBase - depthMm : vBase;
        double vMaxVal = vMax ? vBase : vBase + depthMm;

        setUv(pts.get(ia), uMin, vMin, cu, su);
        setUv(pts.get(ib), uMaxVal, vMin, cu, su);
        setUv(pts.get(ic), uMaxVal, vMaxVal, cu, su);
        setUv(pts.get(id), uMin, vMaxVal, cu, su);

        SpatialGeometry result = copy(geometry);
        result.setOuter(pts);
        return result;
    }

    public SpatialGeometry moveVertex(SpatialGeometry geometry, String vertexId, double x, double y) {
        SpatialGeometry result = copy(geometry);
        int idx = findVertexIndex(result.getOuterVertexIds(), vertexId);
        if (idx >= 0) {
            result.getOuter().get(idx).set(0, x);
            result.getOuter().get(idx).set(1, y);
        }
        return result;
    }

    public List<String> createVertexIds(int count) {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) ids.add("V-" + i);
        return ids;
    }

    public SpatialGeometry ensureVertexIds(SpatialGeometry geometry) {
        if (geometry == null) return null;
        SpatialGeometry result = copy(geometry);
        if (result.getOuterVertexIds() == null || result.getOuterVertexIds().size() != result.getOuter().size()) {
            result.setOuterVertexIds(createVertexIds(result.getOuter().size()));
        }
        return result;
    }

    public List<SpatialIssue> validate(SpatialGeometry geometry) {
        List<SpatialIssue> issues = new ArrayList<>();
        if (geometry == null) {
            issues.add(issue("NULL_GEOMETRY", "几何为空", true));
            return issues;
        }
        List<List<Double>> outer = geometry.getOuter();
        if (outer == null || outer.size() < 3) {
            issues.add(issue("DEGENERATE_RING", "外环顶点不足", true));
            return issues;
        }
        if (!allFinite(outer)) {
            issues.add(issue("NON_FINITE_COORDINATES", "坐标包含非有限数值", true));
            return issues;
        }
        if (Math.abs(ringArea(outer)) < MIN_AREA_MM2) {
            issues.add(issue("DEGENERATE_AREA", "面积过小", true));
        }
        if (hasSelfIntersection(outer)) {
            issues.add(issue("SELF_INTERSECTION", "外环自交", true));
        }
        if (geometry.getHoles() != null) {
            for (int i = 0; i < geometry.getHoles().size(); i++) {
                List<List<Double>> hole = geometry.getHoles().get(i);
                if (hole.size() < 3) {
                    issues.add(issue("DEGENERATE_HOLE", "孔洞 " + i + " 顶点不足", true));
                }
            }
        }
        return issues;
    }

    private SpatialIssue issue(String code, String message, boolean blocks) {
        SpatialIssue i = new SpatialIssue();
        i.setIssueId(UUID.randomUUID().toString().substring(0, 8));
        i.setCode(code);
        i.setSeverity(blocks ? "ERROR" : "WARN");
        i.setSpaceIds(List.of());
        i.setObjectIds(List.of());
        i.setMessage(message);
        i.setPosition(null);
        i.setBlocksConfirmation(blocks);
        return i;
    }

    private boolean allFinite(List<List<Double>> ring) {
        for (List<Double> p : ring) {
            if (p.size() < 2) return false;
            if (!Double.isFinite(p.get(0)) || !Double.isFinite(p.get(1))) return false;
        }
        return true;
    }

    private boolean hasSelfIntersection(List<List<Double>> ring) {
        int n = ring.size();
        for (int i = 0; i < n; i++) {
            List<Double> a1 = ring.get(i);
            List<Double> a2 = ring.get((i + 1) % n);
            for (int j = i + 2; j < n; j++) {
                if (i == 0 && j == n - 1) continue; // adjacent at wrap
                List<Double> b1 = ring.get(j);
                List<Double> b2 = ring.get((j + 1) % n);
                if (segmentsIntersect(a1, a2, b1, b2)) return true;
            }
        }
        return false;
    }

    private boolean segmentsIntersect(List<Double> a1, List<Double> a2, List<Double> b1, List<Double> b2) {
        double ax = a2.get(0) - a1.get(0);
        double ay = a2.get(1) - a1.get(1);
        double bx = b2.get(0) - b1.get(0);
        double by = b2.get(1) - b1.get(1);
        double dx = b1.get(0) - a1.get(0);
        double dy = b1.get(1) - a1.get(1);
        double det = ax * by - ay * bx;
        if (Math.abs(det) < 1e-12) return false;
        double t = (dx * by - dy * bx) / det;
        double u = (dx * ay - dy * ax) / det;
        return t > 1e-9 && t < 1 - 1e-9 && u > 1e-9 && u < 1 - 1e-9;
    }

    private int findVertexIndex(List<String> ids, String vertexId) {
        if (ids == null) return -1;
        for (int i = 0; i < ids.size(); i++) {
            if (Objects.equals(ids.get(i), vertexId)) return i;
        }
        return -1;
    }

    private SpatialGeometry copy(SpatialGeometry geometry) {
        if (geometry == null) return null;
        SpatialGeometry g = new SpatialGeometry();
        g.setType(geometry.getType());
        g.setCoordinateSystem(geometry.getCoordinateSystem());
        g.setOuter(copyPoints(geometry.getOuter()));
        g.setOuterVertexIds(geometry.getOuterVertexIds() == null ? null : new ArrayList<>(geometry.getOuterVertexIds()));
        g.setHoles(copyHoles(geometry.getHoles()));
        g.setHoleVertexIds(geometry.getHoleVertexIds() == null ? null : new ArrayList<>(geometry.getHoleVertexIds()));
        return g;
    }

    private List<List<Double>> copyPoints(List<List<Double>> pts) {
        List<List<Double>> out = new ArrayList<>();
        for (List<Double> p : pts) out.add(new ArrayList<>(p));
        return out;
    }

    private List<List<List<Double>>> copyHoles(List<List<List<Double>>> holes) {
        if (holes == null) return null;
        List<List<List<Double>>> out = new ArrayList<>();
        for (List<List<Double>> h : holes) out.add(copyPoints(h));
        return out;
    }

    private double rectangleWidth(SpatialGeometry geometry, SpatialRectangleFrame frame) {
        return rectangleDimension(geometry, frame, true);
    }

    private double rectangleDepth(SpatialGeometry geometry, SpatialRectangleFrame frame) {
        return rectangleDimension(geometry, frame, false);
    }

    private double rectangleDimension(SpatialGeometry geometry, SpatialRectangleFrame frame, boolean width) {
        if (geometry.getOuterVertexIds() == null) return 0;
        Map<String, List<Double>> map = new HashMap<>();
        for (int i = 0; i < geometry.getOuterVertexIds().size(); i++) {
            map.put(geometry.getOuterVertexIds().get(i), geometry.getOuter().get(i));
        }
        SpatialRectangleFrame.RectangleCornerVertexIds c = frame.getCornerVertexIds();
        List<Double> a = map.get(c.getU0v0());
        List<Double> b = map.get(c.getU1v0());
        List<Double> d = map.get(c.getU0v1());
        if (a == null || b == null || d == null) return 0;
        return width ? distance(a, b) : distance(a, d);
    }

    private double distance(List<Double> a, List<Double> b) {
        return Math.hypot(b.get(0) - a.get(0), b.get(1) - a.get(1));
    }

    private void setUv(List<Double> p, double u, double v, double cu, double su) {
        p.set(0, u * cu - v * su);
        p.set(1, u * su + v * cu);
    }

    private double normalizeAngle(double rad) {
        while (rad <= -Math.PI) rad += Math.PI;
        while (rad > Math.PI) rad -= Math.PI;
        if (rad > Math.PI / 2) rad -= Math.PI;
        if (rad <= -Math.PI / 2) rad += Math.PI;
        return rad;
    }

    private boolean parallel(double a, double b) {
        return Math.abs(normalizeAngle(a - b)) < 1e-6;
    }

    private boolean perpendicular(double a, double b) {
        return Math.abs(normalizeAngle(a - b) - Math.PI / 2) < 1e-6
            || Math.abs(normalizeAngle(a - b) + Math.PI / 2) < 1e-6;
    }

    private int minIndex(double[] u, double[] v) {
        int idx = 0;
        double min = u[0] + v[0];
        for (int i = 1; i < u.length; i++) {
            if (u[i] + v[i] < min) {
                min = u[i] + v[i];
                idx = i;
            }
        }
        return idx;
    }

    private int maxIndex(double[] u, double[] v, boolean byU) {
        int idx = 0;
        double max = byU ? u[0] : v[0];
        for (int i = 1; i < u.length; i++) {
            if ((byU ? u[i] : v[i]) > max) {
                max = byU ? u[i] : v[i];
                idx = i;
            }
        }
        return idx;
    }

    private int argMinSum(double[] u, double[] v, int su, int sv) {
        int best = 0;
        double bestVal = su * u[0] + sv * v[0];
        for (int i = 1; i < u.length; i++) {
            double val = su * u[i] + sv * v[i];
            if (val < bestVal) {
                bestVal = val;
                best = i;
            }
        }
        return best;
    }
}
