package com.rsdp.officeproduct.importer.office.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class RenderPreviewRequest {
    private String clientChangeId;
    private String zoneId;
    private Integer canvasRevision;
    /** Compared with saved server geometry, never trusted as the source of the render. */
    private JsonNode expectedSnapshot;
    private String style;
    private String view;
    private String instructions;
}
