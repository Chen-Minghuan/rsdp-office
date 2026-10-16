package com.rsdp.officeproduct.importer.dto.response;

import java.util.List;

/** Reusable manual design geometry in the unchanged source page's pixel coordinates. */
public record DesignContourCorrection(String name, List<List<Double>> polygon, String sourceAnalysisId,
                                      int sourceAnalysisRevision, int canvasRevision) {}
