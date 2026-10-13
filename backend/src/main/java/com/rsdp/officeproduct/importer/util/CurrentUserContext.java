package com.rsdp.officeproduct.importer.util;

public final class CurrentUserContext {

    private static final ThreadLocal<String> CURRENT_USER = ThreadLocal.withInitial(() -> "system");

    private CurrentUserContext() {
    }

    public static String currentUsername() {
        return CURRENT_USER.get();
    }

    public static void set(String username) {
        CURRENT_USER.set(username);
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
