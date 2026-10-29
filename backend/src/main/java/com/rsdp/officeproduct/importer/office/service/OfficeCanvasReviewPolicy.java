package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.rsdp.officeproduct.importer.common.BusinessException;

import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Gate the legacy workspace's layout stage against the submitted design geometry. */
public final class OfficeCanvasReviewPolicy {
    private OfficeCanvasReviewPolicy() {}

    public static void requireLayoutReady(JsonNode objects, String designZoneId) {
        if (objects == null || !objects.isArray()) throw new BusinessException("请先提供空间轮廓");
        Set<String> ids = new HashSet<>();
        for (JsonNode zone : objects) {
            if (!"zone".equals(zone.path("kind").asText())) continue;
            String id = zone.path("id").asText();
            if (id.isBlank() || !ids.add(id)) throw new BusinessException("空间 ID 缺失或重复");
            requireZoneValid(zone);
        }
        if (ids.isEmpty() || designZoneId == null || !ids.contains(designZoneId)) throw new BusinessException("请选择有效的排版空间");
        OfficeLayoutValidationPolicy.requireConfirmedValid(objects);
    }

    /** 单个空间进入精确排版/设计复核确认前的几何与比例校验。 */
    public static void requireZoneValid(JsonNode zone) {
        if (!"mm".equals(zone.path("reviewUnit").asText())) throw new BusinessException("请先校准空间的实际尺寸");
        JsonNode polygon = zone.path("polygon");
        if (!polygon.isArray() || polygon.size() < 3) throw new BusinessException("空间缺少有效轮廓");
        List<double[]> points = new ArrayList<>();
        double signedArea = 0;
        for (JsonNode point : polygon) {
            if (!point.isArray() || point.size() != 2 || !point.get(0).isNumber() || !point.get(1).isNumber()) throw new BusinessException("空间坐标无效");
            double x = point.get(0).asDouble(), y = point.get(1).asDouble();
            if (!Double.isFinite(x) || !Double.isFinite(y)) throw new BusinessException("空间坐标无效");
            points.add(new double[] { x, y });
        }
        for (int i = 0; i < points.size(); i++) {
            double[] a = points.get(i), b = points.get((i + 1) % points.size());
            if (Math.hypot(a[0] - b[0], a[1] - b[1]) < 0.01) throw new BusinessException("空间存在退化边");
            signedArea += a[0] * b[1] - b[0] * a[1];
            for (int j = i + 2; j < points.size(); j++) {
                if (i == 0 && j == points.size() - 1) continue;
                double[] c = points.get(j), d = points.get((j + 1) % points.size());
                if (Line2D.linesIntersect(a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1])) throw new BusinessException("空间轮廓自交，请先修正");
            }
        }
        if (Math.abs(signedArea) / 2 <= 1) throw new BusinessException("空间面积无效");
    }
}
