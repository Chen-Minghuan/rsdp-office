package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

import java.util.List;

@Data
public class SpatialAnnotation {

    private String annotationId;
    private String text;
    private List<Double> position;
}
