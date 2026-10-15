package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

@Data
public class SpatialIssueDisposition {

    public static final String ACTION_ACKNOWLEDGE = "ACKNOWLEDGE";
    public static final String ACTION_RESOLVE = "RESOLVE";
    public static final String ACTION_REOPEN = "REOPEN";

    private String issueId;
    private String action;
    private String note;
}
