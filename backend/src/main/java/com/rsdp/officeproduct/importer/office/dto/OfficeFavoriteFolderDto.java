package com.rsdp.officeproduct.importer.office.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OfficeFavoriteFolderDto {

    private String folderId;
    private String name;
    private long itemCount;
    private boolean defaultFolder;
    private boolean selected;
}
