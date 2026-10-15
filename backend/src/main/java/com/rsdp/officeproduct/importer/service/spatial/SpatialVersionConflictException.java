package com.rsdp.officeproduct.importer.service.spatial;

import lombok.Getter;

@Getter
public class SpatialVersionConflictException extends RuntimeException {

    private final Long currentRevision;

    public SpatialVersionConflictException(Long currentRevision) {
        super("Version conflict, current revision: " + currentRevision);
        this.currentRevision = currentRevision;
    }
}
