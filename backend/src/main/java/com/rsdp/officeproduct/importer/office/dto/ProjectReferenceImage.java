package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

@Data
public class ProjectReferenceImage {
    /** 本站上传图片的相对路径，如 /uploads/images/xxx.png */
    private String url;
    private String name;
}
