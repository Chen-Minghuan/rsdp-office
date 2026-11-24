package com.rsdp.officeproduct.importer.office.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OfficeComboDto {

    private String comboId;
    private String name;
    private List<OfficeComboItemDto> items;
}
