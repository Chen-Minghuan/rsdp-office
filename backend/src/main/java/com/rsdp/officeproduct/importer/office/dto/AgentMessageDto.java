package com.rsdp.officeproduct.importer.office.dto;

import lombok.Data;

@Data
public class AgentMessageDto {

    private Long messageId;
    private String projectId;
    private String role;
    private String content;
}
