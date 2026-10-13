package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

import java.util.List;

@Data
public class CanvasDto {

    private Long canvasId;
    private String projectId;
    private Integer revision;
    private String mode;
    private String designZoneId;
    private List<Object> objects;
    private List<Object> files;
    private Boolean blank;
}
