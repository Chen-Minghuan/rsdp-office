package com.rsdp.officeproduct.importer.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("office_user_session")
public class OfficeUserSession {
    @TableId
    private String sessionId;
    private String userId;
    private Boolean rememberMe;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private String createdIp;
    private LocalDateTime createdAt;
}
