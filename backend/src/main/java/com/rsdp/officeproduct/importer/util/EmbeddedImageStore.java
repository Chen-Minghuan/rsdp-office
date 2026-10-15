package com.rsdp.officeproduct.importer.util;

import com.rsdp.officeproduct.importer.common.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class EmbeddedImageStore {
    public record Saved(String url, Path newlyCreatedFile) { }

    public Saved save(WorkbookImportSource.Image image) {
        byte[] data = image.data();
        String ext = image.extension().equalsIgnoreCase("jpeg") ? "jpg" : image.extension().toLowerCase();
        if (!matchesSignature(data, ext)) throw new BusinessException("内嵌图片内容与格式不符");
        Path dir = Path.of(System.getProperty("user.dir"), "uploads", "images").toAbsolutePath().normalize();
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        Path target = dir.resolve("excel-" + hash + "." + ext);
        boolean created = false;
        try {
            Files.createDirectories(dir);
            try {
                Files.write(target, data, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                created = true;
            } catch (FileAlreadyExistsException ignored) {
                if (!java.util.Arrays.equals(Files.readAllBytes(target), data))
                    throw new BusinessException("已存图片内容冲突");
            }
        } catch (IOException e) { throw new BusinessException("内嵌图片保存失败", e); }
        String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return new Saved(base + "/uploads/images/" + target.getFileName(), created ? target : null);
    }

    public void deleteIfCreated(Saved saved) {
        if (saved.newlyCreatedFile() == null) return;
        try { Files.deleteIfExists(saved.newlyCreatedFile()); }
        catch (IOException ignored) { /* orphan cleanup can be retried by operator */ }
    }

    private boolean matchesSignature(byte[] data, String ext) {
        if (ext.equals("png")) return data.length > 8 && (data[0] & 0xff) == 0x89
            && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
        if (ext.equals("jpg")) return data.length > 3 && (data[0] & 0xff) == 0xff
            && (data[1] & 0xff) == 0xd8 && (data[2] & 0xff) == 0xff;
        if (ext.equals("webp")) return data.length > 12 && new String(data, 0, 4).equals("RIFF")
            && new String(data, 8, 4).equals("WEBP");
        return false;
    }
}
