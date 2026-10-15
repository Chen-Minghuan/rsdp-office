package com.rsdp.officeproduct.importer.dto.request;

import com.rsdp.officeproduct.importer.dto.response.FloorPlanBBox;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 户型图人工校正提交请求。 */
@Data
public class FloorPlanConfirmRequest {

    @NotNull(message = "空间列表不能为空")
    private List<@Valid RoomItem> rooms;

    /** 人工确认的比例尺（像素:实际mm），可空。 */
    private BigDecimal scaleRatio;

    @Size(max = 64, message = "项目 ID 长度不能超过 64")
    private String projectId;

    private Integer analysisRevision;
    private Integer baseRoomRevision;

    @Data
    public static class RoomItem {

        private String roomId;

        @NotBlank(message = "空间类型不能为空")
        @Size(max = 32, message = "空间类型长度不能超过 32")
        private String roomType;

        @Size(max = 128, message = "空间名称长度不能超过 128")
        private String label;

        @Min(value = 1, message = "开间必须为正数（mm）")
        private Integer widthMm;

        @Min(value = 1, message = "进深必须为正数（mm）")
        private Integer depthMm;

        @Valid
        private FloorPlanBBox bbox;

        /** 确认后的多边形轮廓（毫米坐标），优先用于面积计算。 */
        private List<List<Double>> polygon;
    }
}
