package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

import java.util.List;

@Data
public class SpatialCalibrationSnapshot {

    public static final String STATUS_UNKNOWN = "UNKNOWN";
    public static final String STATUS_ESTIMATED = "ESTIMATED";
    public static final String STATUS_CONFIRMED = "CONFIRMED";

    private Long revision;
    private String status;
    private List<Double> sourceToWorld;
}
