package com.rsdp.officeproduct.importer.office.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class RenderPreviewRequest {
    private String clientChangeId;
    private String zoneId;
    private Integer canvasRevision;
    private String confirmationId;
    /** Compared with saved server geometry, never trusted as the source of the render. */
    private JsonNode expectedSnapshot;
    private String style;
    private String view;
    private String instructions;
    /**
     * Optional white-model viewport screenshot (data:image/png;base64,...). When present it becomes the
     * primary structure reference (whiteModelView); the server decodes and validates it, never trusting pixels blindly.
     */
    private String viewImage;
    /** Optional controlled render experiment; null values preserve the current v10 baseline. */
    private String promptMode;
    private String referenceMode;
    private Boolean promptExtend;
    private Long seed;
    private Integer sampleIndex;
}
