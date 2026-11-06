package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.entity.OfficeAuditLog;
import com.rsdp.officeproduct.importer.office.mapper.OfficeAuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 授权操作审计。与业务写入同事务，审计失败即整体回滚，不允许"操作成功但无日志"。
 */
@Service
@RequiredArgsConstructor
public class OfficeAuditService {

    private final OfficeAuditLogMapper auditLogMapper;
    private final CurrentUserService currentUserService;

    public void record(String action, String targetType, String targetId, String detail) {
        OfficeUser operator = currentUserService.authEnabled() ? currentUserService.currentUser() : null;
        OfficeAuditLog log = new OfficeAuditLog();
        log.setActorUserId(operator != null ? operator.getUserId() : "system");
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setDetail(detail == null ? "" : detail);
        log.setCreatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }
}
