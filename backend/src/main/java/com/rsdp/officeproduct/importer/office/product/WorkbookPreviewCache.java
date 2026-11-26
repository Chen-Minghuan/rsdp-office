package com.rsdp.officeproduct.importer.office.product;

import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.rsdp.officeproduct.importer.office.product.LocalProductModels.*;

@Slf4j
@Component
public class WorkbookPreviewCache {
    private static final long MAX_PREVIEW_BYTES = 150 * 1024L * 1024;
    private static final long MAX_TEMP_BYTES = 1024 * 1024L * 1024;
    private static final Duration IDLE_LIMIT = Duration.ofHours(4);
    private final Map<UUID, Session> sessions = new HashMap<>();
    private long totalBytes;

    private record Attachment(Path path, String mediaType, int bytes) {}
    record WorkbookSource(String fileName, long fileBytes) {}
    record Materialized(List<Input> products, WorkbookSource source) {}
    static class Limit extends BusinessException {
        Limit(String message) { super(message); }
    }

    final class Session implements AutoCloseable {
        private final UUID id = UUID.randomUUID();
        private final String workspace;
        private final String user;
        private final Path directory;
        private final WorkbookSource source;
        private final Map<String, Attachment> files = new HashMap<>();
        private long bytes;
        private Instant lastAccess = Instant.now();
        private boolean kept;

        private Session(OfficeUser actor, WorkbookSource source) throws IOException {
            workspace = actor.getWorkspaceId();
            user = actor.getUserId();
            directory = Files.createTempDirectory("office-product-preview-");
            this.source = source;
        }

        ImageInput store(ImageInput input) throws IOException {
            return store(key(input.content()), input);
        }

        ImageInput store(String key, ImageInput input) throws IOException {
            synchronized (WorkbookPreviewCache.this) {
                if (!files.containsKey(key)) {
                    int size = input.content().length;
                    if (bytes + size > MAX_PREVIEW_BYTES) throw new Limit("工作簿中的内嵌图片合计超过 150 MB，请拆分后上传");
                    if (totalBytes + size > MAX_TEMP_BYTES) throw new Limit("Excel 预览空间暂时已满，请稍后重试");
                    Path path = directory.resolve(key);
                    Files.write(path, input.content());
                    files.put(key, new Attachment(path, input.mediaType(), size));
                    bytes += size;
                    totalBytes += size;
                }
                return new ImageInput(key, files.get(key).mediaType(), null);
            }
        }

        UUID id() { return id; }

        Map<String, Integer> sizes() {
            var result = new HashMap<String, Integer>();
            files.forEach((key, file) -> result.put(key, file.bytes()));
            return Map.copyOf(result);
        }

        void keep() {
            synchronized (WorkbookPreviewCache.this) {
                kept = true;
                lastAccess = Instant.now();
                sessions.put(id, this);
            }
        }

        @Override
        public void close() {
            synchronized (WorkbookPreviewCache.this) {
                if (!kept) discard(this);
            }
        }
    }

    Session open(OfficeUser actor, WorkbookSource source) throws IOException {
        expire();
        return new Session(actor, source);
    }

    synchronized ImageInput read(UUID id, String image, OfficeUser actor) {
        return load(attachment(requireSession(id, actor), image));
    }

    synchronized Materialized materialize(SaveRequest request, OfficeUser actor) {
        Session session = requireSession(request.previewId(), actor);
        long bytes = 0;
        var inputs = new ArrayList<Input>();
        for (Input product : request.products()) {
            if (product == null || product.id() != null || product.variants() == null) {
                throw new BusinessException("Excel 导入的商品资料不符");
            }
            var images = new ArrayList<ImageInput>();
            if (product.images() != null) {
                if (product.images().size() > MAX_IMAGES) throw new BusinessException("每个商品最多 6 张图片");
                for (ImageInput image : product.images()) {
                    if (image == null || image.id() == null || image.content() != null) {
                        throw new BusinessException("Excel 预览图片引用不符");
                    }
                    var file = attachment(session, image.id());
                    bytes += file.bytes();
                    if (bytes > MAX_BATCH_IMAGE_BYTES) throw new BusinessException("每个处理批次的图片合计不可超过 12 MB");
                    images.add(load(file));
                }
            }
            inputs.add(new Input(null, product.revision(), product.name(), product.categoryCode(),
                product.factoryCode(), product.factoryModel(), product.variants(), images));
        }
        return new Materialized(inputs, session.source);
    }

    private Session requireSession(UUID id, OfficeUser actor) {
        var session = sessions.get(id);
        if (session == null || !Objects.equals(session.workspace, actor.getWorkspaceId())
                || !Objects.equals(session.user, actor.getUserId()) || expired(session)) {
            throw new BusinessException(404, "Excel 预览已失效，请重新上传文件");
        }
        session.lastAccess = Instant.now();
        return session;
    }

    private Attachment attachment(Session session, String key) {
        var file = session.files.get(key);
        if (file == null) throw new BusinessException(404, "Excel 预览中没有这张图片");
        return file;
    }

    private ImageInput load(Attachment image) {
        try {
            return new ImageInput(null, image.mediaType(), Files.readAllBytes(image.path()));
        } catch (IOException error) {
            throw new BusinessException("Excel 图片暂时无法读取，请重新上传文件");
        }
    }

    static String key(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private boolean expired(Session session) {
        return session.lastAccess.plus(IDLE_LIMIT).isBefore(Instant.now());
    }

    @Scheduled(fixedDelay = 600_000)
    synchronized void expire() {
        sessions.values().removeIf(session -> {
            if (!expired(session)) return false;
            discard(session);
            return true;
        });
    }

    @PreDestroy
    synchronized void shutdown() {
        sessions.values().forEach(this::discard);
        sessions.clear();
    }

    private void discard(Session session) {
        totalBytes -= session.bytes;
        try (var contents = Files.list(session.directory)) {
            for (Path file : contents.toList()) Files.deleteIfExists(file);
            Files.deleteIfExists(session.directory);
        } catch (IOException error) {
            log.warn("Excel 预览文件清理失败", error);
        }
    }
}
