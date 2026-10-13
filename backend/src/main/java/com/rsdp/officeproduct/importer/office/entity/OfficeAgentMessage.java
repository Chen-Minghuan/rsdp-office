package com.rsdp.officeproduct.importer.office.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("office_agent_message")
public class OfficeAgentMessage {

    @TableId
    private Long messageId;

    private String projectId;
    private String role;
    private String content;
    private LocalDateTime createdAt;
}
