package com.rsdp.officeproduct.importer.common;

/** 已登录但无目标项目权限；与参数类 BusinessException 区分，映射为 403。 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}
