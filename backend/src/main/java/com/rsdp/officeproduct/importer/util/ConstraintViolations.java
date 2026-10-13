package com.rsdp.officeproduct.importer.util;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConstraintViolations {

    private static final Pattern CONSTRAINT_NAME_PATTERN = Pattern.compile("constraint \"([^\"]+)\"");
    private static final String SQLSTATE_UNIQUE = "23505";
    private static final String SQLSTATE_FOREIGN_KEY = "23503";

    private ConstraintViolations() {
    }

    public static String extractConstraintName(DataIntegrityViolationException e) {
        String message = e.getMessage();
        if (message == null) {
            return null;
        }
        Matcher matcher = CONSTRAINT_NAME_PATTERN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static boolean isUniqueViolation(DataIntegrityViolationException e) {
        if (e instanceof DuplicateKeyException) {
            return true;
        }
        String sqlState = sqlStateOf(e);
        if (sqlState != null) {
            return SQLSTATE_UNIQUE.equals(sqlState);
        }
        String message = e.getMessage();
        return message != null
            && (message.contains("duplicate key") || message.contains("unique constraint")
                || message.contains("Duplicate entry"));
    }

    public static String toUserMessage(DataIntegrityViolationException e, String uniqueConflictMessage) {
        if (isUniqueViolation(e)) {
            return uniqueConflictMessage;
        }
        String constraint = extractConstraintName(e);
        String suffix = constraint != null ? "（约束: " + constraint + "）" : "";
        return "数据完整性校验失败" + suffix + "，请检查数据后重试";
    }

    private static String sqlStateOf(DataIntegrityViolationException e) {
        Throwable t = e.getCause();
        while (t != null) {
            if (t instanceof SQLException sqlException && sqlException.getSQLState() != null) {
                return sqlException.getSQLState();
            }
            t = t.getCause();
        }
        return null;
    }
}
