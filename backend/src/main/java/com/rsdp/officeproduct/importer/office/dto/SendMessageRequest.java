package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

@Data
public class SendMessageRequest {

    private String role;
    private String content;
}
