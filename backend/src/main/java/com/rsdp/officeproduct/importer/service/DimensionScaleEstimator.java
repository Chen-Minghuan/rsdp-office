package com.rsdp.officeproduct.importer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.response.DimensionCalibrationReport;
import com.rsdp.officeproduct.importer.dto.response.DimensionCalibrationReport.Reference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Deterministic validation of model-read dimension evidence; never trusts model-calculated scales. */
public final class DimensionScaleEstimator {
    public static final double RELATIVE_TOLERANCE = 0.03;
    private static final Pattern LENGTH = Pattern.compile("^\\s*([0-9]+(?:[，,][0-9]{3})*(?:\\.[0-9]+)?)\\s*(毫米|厘米|米|mm|cm|m)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNIT_NOTE = Pattern.compile("(?:单位|units?)\\s*[:：]?\\s*(mm|cm|m|毫米|厘米|米)(?:\\s|$|[，,。;；])", Pattern.CASE_INSENSITIVE);
    private DimensionScaleEstimator() {}

    public static DimensionCalibrationReport estimate(JsonNode input, int width, int height, String hash, String reportId, String assumedUnit) {
        if (!Set.of("mm", "cm", "m").contains(assumedUnit)) throw new BusinessException("尺寸单位必须为 mm、cm 或 m");
        if (width <= 0 || height <= 0) throw new BusinessException("标准底图尺寸无效");
        var result = new DimensionCalibrationReport();
        result.setReportId(reportId);
        result.setImageWidth(width);
        result.setImageHeight(height);
        result.setImageSha256(hash);
        result.setAssumedUnit(assumedUnit);
        List<Reference> valid = new ArrayList<>();
        int index = 0;
        if (input != null && input.isArray()) for (JsonNode item : input) {
            if (++index > 64) { result.getWarnings().add("尺寸标注超过 64 条，仅校验前 64 条"); break; }
            var ref = new Reference();
            ref.setReferenceId("DIM-" + index);
            ref.setText(item.path("text").asText(""));
            ref.setConfidence(item.path("confidence").asText("low"));
            ref.setStatus("REJECTED");
            result.getReferences().add(ref);
            var match = LENGTH.matcher(ref.getText());
            if (!match.matches()) { ref.setReason("不是清晰的线性长度标注"); continue; }
            double value;
            try { value = Double.parseDouble(match.group(1).replace(",", "").replace("，", "")); }
            catch (NumberFormatException e) { ref.setReason("长度数字无效"); continue; }
            String unit = normalizeUnit(match.group(2));
            if (unit == null) {
                String reportedUnit = normalizeUnit(item.path("unit").asText(""));
                String evidence = item.path("unitEvidence").asText("").toLowerCase(Locale.ROOT);
                // A claimed unit without visible drawing-note evidence stays an explicit assumption.
                var note = UNIT_NOTE.matcher(evidence);
                boolean visibleUnitNote = reportedUnit != null && note.find() && reportedUnit.equals(normalizeUnit(note.group(1)));
                if (visibleUnitNote) unit = reportedUnit;
            }
            ref.setUnitAssumed(unit == null);
            if (unit == null) unit = assumedUnit;
            ref.setUnit(unit);
            double millimetres = value * (unit.equals("m") ? 1000 : unit.equals("cm") ? 10 : 1);
            if (!Double.isFinite(millimetres) || millimetres <= 0 || millimetres > 100_000_000) { ref.setReason("实际长度超出有效范围"); continue; }
            ref.setLengthMm(millimetres);
            List<Double> start = point(item.path("start"), width, height), end = point(item.path("end"), width, height);
            if (start == null || end == null) { ref.setReason("缺少有效尺寸线端点"); continue; }
            ref.setStart(start); ref.setEnd(end);
            double dx = end.get(0) - start.get(0), dy = end.get(1) - start.get(1);
            double pixels = Math.hypot(dx, dy);
            ref.setPixelLength(pixels);
            if (pixels < Math.max(12, Math.min(width, height) * 0.02)) { ref.setReason("参考线太短，端点误差过大"); continue; }
            if (!Set.of("high", "mid").contains(ref.getConfidence())) { ref.setReason("尺寸文字或端点置信度低"); continue; }
            ref.setAxis(Math.abs(dy) <= Math.abs(dx) * 0.1 ? "HORIZONTAL" : Math.abs(dx) <= Math.abs(dy) * 0.1 ? "VERTICAL" : "ALIGNED");
            ref.setMmPerPixel(millimetres / pixels);
            Reference duplicate = valid.stream().filter(previous -> sameSegment(previous, ref)).findFirst().orElse(null);
            if (duplicate != null && Math.abs(duplicate.getLengthMm() - millimetres) / millimetres < 0.001) { ref.setReason("重复的同一条尺寸线，不计独立证据"); continue; }
            ref.setStatus("VALID");
            valid.add(ref);
        }
        result.setValidCount(valid.size());
        result.setUnitAssumed(valid.stream().anyMatch(Reference::isUnitAssumed));
        if (result.isUnitAssumed()) result.getWarnings().add("部分尺寸未注明单位，按 " + assumedUnit + " 形成候选；请核对或切换单位");
        if (valid.size() < 2) {
            result.setStatus(valid.isEmpty() ? "NO_DIMENSIONS" : "INSUFFICIENT");
            result.getWarnings().add(valid.isEmpty() ? "未获得可用尺寸标注，请使用两点校准" : "仅有一条独立参考，不能校验比例一致性，请使用两点校准");
            return result;
        }
        // Contradictory readings of the same line are a conflict, not extra votes.
        for (int i = 0; i < valid.size(); i++) for (int j = i + 1; j < valid.size(); j++) {
            if (sameSegment(valid.get(i), valid.get(j))) return conflict(result, "同一尺寸线出现互相矛盾的数字");
        }
        Double horizontal = axisMedian(valid, "HORIZONTAL"), vertical = axisMedian(valid, "VERTICAL");
        if (horizontal != null && vertical != null && deviation(horizontal, vertical) > RELATIVE_TOLERANCE) {
            return conflict(result, "横向与纵向比例不一致，可能存在读数错误或图像拉伸，请人工校准");
        }
        List<Reference> best = List.of();
        for (Reference seed : valid) {
            List<Reference> group = valid.stream().filter(ref -> deviation(ref.getMmPerPixel(), seed.getMmPerPixel()) <= RELATIVE_TOLERANCE).toList();
            double median = median(group);
            group = group.stream().filter(ref -> deviation(ref.getMmPerPixel(), median) <= RELATIVE_TOLERANCE).toList();
            if (group.size() > best.size()) best = group;
        }
        if (best.size() < 2 || best.size() * 2 <= valid.size()) return conflict(result, "多条尺寸无法形成一致的多数比例，请人工核对");
        double scale = median(best);
        result.setMmPerPixel(scale);
        result.setAcceptedCount(best.size());
        result.setMaxDeviationPercent(best.stream().mapToDouble(ref -> deviation(ref.getMmPerPixel(), scale) * 100).max().orElse(0));
        for (Reference ref : valid) {
            boolean accepted = best.contains(ref);
            ref.setStatus(accepted ? "ACCEPTED" : "OUTLIER");
            if (!accepted) ref.setReason("与一致比例的偏差超过 3%，未采用");
        }
        if (best.size() != valid.size()) result.getWarnings().add("已排除不一致的尺寸标注，请对照原图检查");
        if (horizontal == null || vertical == null) result.getWarnings().add("缺少横纵双向交叉验证，请核对另一方向是否保持等比例");
        result.setStatus("READY");
        return result;
    }

    private static DimensionCalibrationReport conflict(DimensionCalibrationReport result, String reason) {
        result.setStatus("CONFLICT");
        result.getWarnings().add(reason);
        return result;
    }
    private static Double axisMedian(List<Reference> refs, String axis) {
        List<Reference> matches = refs.stream().filter(ref -> axis.equals(ref.getAxis())).toList();
        return matches.isEmpty() ? null : median(matches);
    }
    private static double median(List<Reference> refs) {
        List<Double> values = refs.stream().map(Reference::getMmPerPixel).sorted(Comparator.naturalOrder()).toList();
        int index = values.size() / 2;
        return values.size() % 2 == 0 ? (values.get(index - 1) + values.get(index)) / 2 : values.get(index);
    }
    private static double deviation(double a, double b) { return Math.abs(a - b) / Math.min(a, b); }
    private static List<Double> point(JsonNode node, int width, int height) {
        if (!node.isArray() || node.size() != 2 || !node.get(0).isNumber() || !node.get(1).isNumber()) return null;
        double x = node.get(0).asDouble(), y = node.get(1).asDouble();
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || x > 1000 || y < 0 || y > 1000) return null;
        return List.of(x * width / 1000, y * height / 1000);
    }
    private static String normalizeUnit(String unit) {
        if (unit == null) return null;
        return switch (unit.toLowerCase(Locale.ROOT).trim()) { case "mm", "毫米" -> "mm"; case "cm", "厘米" -> "cm"; case "m", "米" -> "m"; default -> null; };
    }
    private static boolean sameSegment(Reference a, Reference b) {
        return (near(a.getStart(), b.getStart()) && near(a.getEnd(), b.getEnd())) || (near(a.getStart(), b.getEnd()) && near(a.getEnd(), b.getStart()));
    }
    private static boolean near(List<Double> a, List<Double> b) { return Math.hypot(a.get(0) - b.get(0), a.get(1) - b.get(1)) <= 2; }
}
