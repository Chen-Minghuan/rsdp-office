package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

/**
 * 设计复核确认的快照比对工具（V1.1 简化方案）：
 * 确认时服务端保存空间影响布局字段的快照，之后保存时逐字段比对，不一致即置 STALE。
 * 快照只由服务端生成与比对，前端不解析。
 */
public final class OfficeSpaceReviewSupport {

    /** 参与比对的字段：身份、毫米几何、比例、用途与建筑约束；名称/备注/颜色等纯展示字段不参与。 */
    private static final String[] SNAPSHOT_FIELDS = {
        "id", "zoneType", "reviewUnit", "mmPerSourceUnit",
        "actualWidthMm", "actualDepthMm", "polygon", "x", "y", "w", "h", "obstacles",
    };

    private OfficeSpaceReviewSupport() {}

    public static ObjectNode snapshot(ObjectMapper mapper, JsonNode zone) {
        ObjectNode snapshot = mapper.createObjectNode();
        for (String field : SNAPSHOT_FIELDS) {
            JsonNode value = zone.get(field);
            if (value != null && !value.isNull()) {
                snapshot.set(field, value.deepCopy());
            }
        }
        return snapshot;
    }

    /** 深度等价：数值按 double 比较（1200 与 1200.0 视为相同），数组按顺序，对象按字段集合。 */
    public static boolean jsonEquivalent(JsonNode a, JsonNode b) {
        if (a == null || a.isMissingNode() || a.isNull()) {
            return b == null || b.isMissingNode() || b.isNull();
        }
        if (b == null || b.isMissingNode() || b.isNull()) {
            return false;
        }
        if (a.isNumber() && b.isNumber()) {
            return Double.compare(a.doubleValue(), b.doubleValue()) == 0;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!jsonEquivalent(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = a.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (!jsonEquivalent(entry.getValue(), b.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }
}
