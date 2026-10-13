package com.rsdp.officeproduct.importer.controller;

import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/images")
@RequiredArgsConstructor
@Validated
public class ImageUploadController {

    private static final long MAX_IMAGE_SIZE = 10 * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    @PostMapping("/upload")
    public Result<List<String>> upload(@RequestPart("files") List<MultipartFile> files,
                                       HttpServletRequest request) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new BusinessException("请选择要上传的图片");
        }
        Path uploadDir = Paths.get(System.getProperty("user.dir"), "uploads", "images");
        if (!Files.exists(uploadDir)) {
            Files.createDirectories(uploadDir);
        }

        String baseUrl = ServletUriComponentsBuilder.fromRequestUri(request)
            .replacePath("/uploads/images")
            .toUriString();

        List<String> urls = files.stream().map(file -> saveFile(file, uploadDir, baseUrl)).toList();
        return Result.ok(urls);
    }

    private String saveFile(MultipartFile file, Path uploadDir, String baseUrl) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("存在空文件");
        }
        String contentType = file.getContentType();
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new BusinessException("仅支持 JPG/PNG/WebP 图片");
        }
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new BusinessException("单张图片不能超过 10MB");
        }
        String ext = extension(file.getOriginalFilename());
        String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = uploadDir.resolve(filename);
        try {
            file.transferTo(target);
        } catch (IOException e) {
            throw new BusinessException("图片保存失败: " + file.getOriginalFilename(), e);
        }
        return baseUrl + "/" + filename;
    }

    private String extension(String filename) {
        if (filename == null || !filename.contains(".")) return "jpg";
        String ext = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
        return List.of("jpg", "jpeg", "png", "webp").contains(ext) ? ext : "jpg";
    }
}
