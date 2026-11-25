package com.rsdp.officeproduct.importer.office.product;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.service.CurrentUserService;
import com.rsdp.officeproduct.importer.office.service.OfficeCatalogAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.rsdp.officeproduct.importer.office.product.LocalProductModels.*;

@Service
@RequiredArgsConstructor
public class LocalProductService {
    private static final int PAGE_SIZE = 30;
    private static final RowMapper<Summary> SUMMARY = (r, i) -> new Summary(
        r.getString("product_id"), r.getInt("revision"), r.getString("name"), r.getString("category_code"),
        r.getString("factory_code"), r.getString("factory_model"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUserService currentUser;
    private final OfficeCatalogAccess catalogAccess;
    private final WorkbookPreviewCache previewCache;
    private final LocalCatalogReader catalogReader;

    public Page list(int page, String search) {
        OfficeUser user = requireMember();
        String keyword = search.strip();
        if (keyword.length() > 120) throw new BusinessException("搜索关键词最多 120 个字符");
        long offset = Math.max(0L, (long) page - 1) * PAGE_SIZE;
        List<CatalogItem> rows = catalogReader.rows(user.getWorkspaceId(), keyword, "", "", offset, PAGE_SIZE + 1);
        return new Page(canWrite(user), rows.stream().limit(PAGE_SIZE).toList(), rows.size() > PAGE_SIZE);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(String productId) {
        OfficeUser user = requireMember();
        String workspace = user.getWorkspaceId();
        Summary product = find(workspace, productId);
        List<Variant> variants = jdbc.query("""
            select * from office_product_variant where workspace_id = ? and product_id = ? and active
            order by position
            """, (r, i) -> new Variant(r.getString("variant_id"), r.getString("display_name"),
            r.getBigDecimal("width_mm"), r.getBigDecimal("depth_mm"), r.getBigDecimal("height_mm"),
            r.getString("size_text"), r.getString("color_text"), r.getString("material_text")), workspace, productId);
        List<ImageRef> images = jdbc.query("""
            select image_id, media_type from office_product_image
            where workspace_id = ? and product_id = ? and position is not null order by position, image_id
            """, (r, i) -> new ImageRef(r.getString(1), r.getString(2)), workspace, productId);
        return new Detail(product, variants, images, canWrite(user));
    }

    public ImageInput image(String id) {
        return jdbc.query("""
            select image_id, media_type, content from office_product_image
            where workspace_id = ? and image_id = ? and position is not null
            """, (r, i) -> new ImageInput(r.getString(1), r.getString(2), r.getBytes(3)),
            requireMember().getWorkspaceId(), id).stream().findFirst().orElseThrow(() -> new BusinessException(404, "图片不存在"));
    }

    @Transactional
    public List<Saved> save(SaveRequest request) {
        OfficeUser user = requireWriter();
        String workspace = user.getWorkspaceId();
        if (request.batchId() == null || request.products() == null || request.products().isEmpty()
                || request.products().size() > MAX_PRODUCTS) {
            throw new BusinessException("每批须包含 1–100 个商品并提供批次编号");
        }
        String boundFactory = boundFactory(workspace);
        // 同一工作区的保存按事务串行，保护批次重放、型号唯一和导入归属。
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", (r, i) -> 0, "office-local-products:" + workspace);
        boolean usesPreview = request.previewId() != null;
        String requestHash = usesPreview ? hash(writeJson(request)) : null;
        if (usesPreview) {
            List<Saved> previous = previousSave(user, request.batchId(), requestHash);
            if (previous != null) return previous;
        }
        var submitted = usesPreview ? previewCache.materialize(request, user).products() : request.products();
        List<Input> inputs = submitted.stream().map(input -> {
            String factory = boundFactory == null && input != null && input.id() != null
                ? find(workspace, input.id()).factoryCode() : boundFactory;
            return normalize(input, factory);
        }).toList();
        long newImageBytes = inputs.stream().flatMap(input -> input.images().stream()).filter(image -> image.id() == null)
            .mapToLong(image -> image.content().length).sum();
        if (newImageBytes > MAX_BATCH_IMAGE_BYTES) throw new BusinessException("每批商品图片合计不可超过 12 MB");
        if (!usesPreview) {
            requestHash = hash(writeJson(inputs));
            List<Saved> previous = previousSave(user, request.batchId(), requestHash);
            if (previous != null) return previous;
        }

        Set<List<String>> models = new HashSet<>();
        Set<String> editedIds = new HashSet<>();
        List<ProductSnapshot> products = new ArrayList<>();
        long imageBytes = 0;
        for (Input input : inputs) {
            if (!models.add(List.of(input.factoryCode(), input.factoryModel()))
                    || (input.id() != null && !editedIds.add(input.id()))) {
                throw new BusinessException("同一批中商品或厂内型号重复");
            }
            ProductSnapshot saved = saveProduct(workspace, user.getUserId(), input, boundFactory != null);
            imageBytes += saved.images().stream().mapToLong(image -> image.content().length).sum();
            if (imageBytes > MAX_BATCH_IMAGE_BYTES) throw new BusinessException("每批商品图片合计不可超过 12 MB");
            products.add(saved);
        }
        List<Saved> result = products.stream().map(p -> new Saved(p.id(), p.revision(), p.name())).toList();
        var delivery = boundFactory == null ? null
            : writeJson(new Delivery(1, request.batchId(), workspace, user.getUserId(), user.getDisplayName(), products));
        jdbc.update("""
            insert into office_product_outbox(workspace_id, batch_id, actor_id, request_hash, payload, result)
            values (?, ?, ?, ?, ?::jsonb, ?::jsonb)
            """, workspace, request.batchId(), user.getUserId(), requestHash, delivery, writeJson(result));
        return result;
    }

    private List<Saved> previousSave(OfficeUser user, UUID batchId, String hash) {
        return jdbc.query("""
            select actor_id, request_hash, result::text from office_product_outbox where workspace_id = ? and batch_id = ?
            """, (r, i) -> {
                if (!user.getUserId().equals(r.getString(1)) || !hash.equals(r.getString(2))) {
                    throw new BusinessException(409, "此批次编号已用于另一项保存");
                }
                return savedResult(r.getString(3));
            }, user.getWorkspaceId(), batchId).stream().findFirst().orElse(null);
    }

    private ProductSnapshot saveProduct(String workspace, String actor, Input input, boolean ownFactory) {
        String id = input.id() == null ? UUID.randomUUID().toString() : input.id();
        int revision = 1;
        boolean duplicate = Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists (select 1 from office_product where workspace_id = ? and (? = '' or factory_code = ?)
                and factory_model = ? and product_id <> ?)
            """, Boolean.class, workspace, input.factoryCode(), input.factoryCode(), input.factoryModel(), id));
        if (duplicate) throw new BusinessException(409, "本工作区已存在商品型号 " + input.factoryModel());
        if (input.id() == null) {
            jdbc.update("""
                insert into office_product(product_id, workspace_id, name, category_code, factory_code, factory_model,
                    revision, created_by, updated_by) values (?, ?, ?, ?, ?, ?, 1, ?, ?)
                """, id, workspace, input.name(), input.categoryCode(), input.factoryCode(), input.factoryModel(), actor, actor);
        } else {
            Summary current = find(workspace, id);
            if (ownFactory && !current.factoryCode().isEmpty() && !current.factoryCode().equals(input.factoryCode())) {
                throw new BusinessException(409, "该商品的历史供应厂与工作区绑定不一致，请联系管理员核对");
            }
            if (!Objects.equals(input.revision(), current.revision())) {
                throw new BusinessException(409, "商品已被修改，请刷新后重新编辑");
            }
            revision = current.revision() + 1;
            jdbc.update("""
                update office_product set name = ?, category_code = ?, factory_code = ?, factory_model = ?,
                    revision = ?, updated_by = ?, updated_at = now()
                where workspace_id = ? and product_id = ?
                """, input.name(), input.categoryCode(), input.factoryCode(), input.factoryModel(),
                revision, actor, workspace, id);
        }
        List<SubmittedVariant> variants = saveVariants(workspace, id, input.variants());
        List<ImageInput> images = saveImages(workspace, id, input.images());
        return new ProductSnapshot(id, revision, input.name(), input.categoryCode(), input.factoryCode(),
            input.factoryModel(), variants, images);
    }

    private List<SubmittedVariant> saveVariants(String workspace, String product, List<Variant> inputs) {
        jdbc.update("update office_product_variant set active = false where workspace_id = ? and product_id = ?",
            workspace, product);
        List<SubmittedVariant> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Variant variant : inputs) {
            String id = variant.id() == null ? UUID.randomUUID().toString() : variant.id();
            if (!seen.add(id)) throw new BusinessException("商品规格重复");
            if (variant.id() == null) {
                jdbc.update("""
                    insert into office_product_variant(variant_id, workspace_id, product_id, display_name,
                        width_mm, depth_mm, height_mm, size_text, color_text, material_text, position)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, workspace, product, variant.name(), variant.widthMm(), variant.depthMm(), variant.heightMm(),
                    variant.sizeText(), variant.color(), variant.material(), result.size());
            } else if (jdbc.update("""
                update office_product_variant set display_name = ?, width_mm = ?, depth_mm = ?, height_mm = ?,
                    size_text = ?, color_text = ?, material_text = ?, position = ?, active = true
                where workspace_id = ? and product_id = ? and variant_id = ?
                """, variant.name(), variant.widthMm(), variant.depthMm(), variant.heightMm(), variant.sizeText(),
                variant.color(), variant.material(), result.size(), workspace, product, id) != 1) {
                throw new BusinessException("规格不属于当前商品");
            }
            var dimensions = new Dimensions(variant.widthMm(), variant.depthMm(), variant.heightMm(), "mm");
            result.add(new SubmittedVariant(id, variant.name(), dimensions, variant.sizeText(), variant.color(), variant.material()));
        }
        return result;
    }

    private List<ImageInput> saveImages(String workspace, String product, List<ImageInput> inputs) {
        List<ImageInput> current = jdbc.query("""
            select image_id, media_type, content from office_product_image
            where workspace_id = ? and product_id = ? and position is not null
            """, (r, i) -> new ImageInput(r.getString(1), r.getString(2), r.getBytes(3)), workspace, product);
        jdbc.update("update office_product_image set position = null where workspace_id = ? and product_id = ?",
            workspace, product);
        List<ImageInput> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ImageInput input : inputs) {
            ImageInput image;
            if (input.id() == null) {
                image = new ImageInput(UUID.randomUUID().toString(), input.mediaType(), input.content());
                jdbc.update("""
                    insert into office_product_image(image_id, workspace_id, product_id, media_type, content, position)
                    values (?, ?, ?, ?, ?, ?)
                    """, image.id(), workspace, product, image.mediaType(), image.content(), result.size());
            } else {
                image = current.stream().filter(item -> item.id().equals(input.id())).findFirst()
                    .orElseThrow(() -> new BusinessException("图片不属于当前商品"));
                jdbc.update("update office_product_image set position = ? where image_id = ?", result.size(), image.id());
            }
            if (!seen.add(image.id())) throw new BusinessException("商品图片重复");
            result.add(image);
        }
        return result;
    }

    private Summary find(String workspace, String id) {
        return jdbc.query("select * from office_product where workspace_id = ? and product_id = ?", SUMMARY,
            workspace, id).stream().findFirst().orElseThrow(() -> new BusinessException(404, "商品不存在"));
    }

    OfficeUser requireWriter() {
        OfficeUser user = requireMember();
        if (!canWrite(user)) throw new AccessDeniedException("当前账号没有商品录入权限");
        return user;
    }

    String boundFactory(String workspace) {
        return catalogAccess.boundFactoryCode(workspace);
    }

    boolean modelExists(String workspace, String factoryCode, String factoryModel) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            select exists (select 1 from office_product where workspace_id = ? and (? = '' or factory_code = ?) and factory_model = ?)
            """, Boolean.class, workspace, factoryCode, factoryCode, factoryModel));
    }

    private OfficeUser requireMember() {
        OfficeUser user = currentUser.currentUser();
        if (user == null || user.getWorkspaceId() == null) throw new AccessDeniedException("请使用工作区账号登录");
        return user;
    }

    public static boolean canWrite(OfficeUser user) {
        return user != null && user.getWorkspaceId() != null
            && ("DEALER".equals(user.getRole()) || Boolean.TRUE.equals(user.getProductEntryEnabled()));
    }

    Input normalize(Input input, String boundFactory) {
        if (input == null || input.variants() == null || input.variants().isEmpty() || input.variants().size() > MAX_VARIANTS) {
            throw new BusinessException("每个商品须有 1–30 个规格");
        }
        var variants = input.variants().stream().map(v -> {
            if (v == null) throw new BusinessException("规格资料不能为空");
            return new Variant(v.id(), required(v.name(), "规格名称", 128), mm(v.widthMm(), "宽度"),
                mm(v.depthMm(), "进深"), v.heightMm() == null ? null : mm(v.heightMm(), "高度"),
                optional(v.sizeText(), "尺寸原文", 64), optional(v.color(), "颜色", 64), optional(v.material(), "材质", 128));
        }).toList();
        var images = input.images() == null ? List.<ImageInput>of() : input.images();
        if (images.size() > MAX_IMAGES) throw new BusinessException("每个商品最多 6 张图片");
        for (ImageInput image : images) {
            if (image == null || (image.id() != null && image.content() != null)) throw new BusinessException("图片资料不符");
            if (image.id() == null) validateImage(image);
        }
        return new Input(input.id(), input.revision(), required(input.name(), "商品名称", 200),
            required(input.categoryCode(), "分类编码", 16).toUpperCase(Locale.ROOT),
            boundFactory == null || boundFactory.isEmpty() ? "" : required(boundFactory, "供应厂编码", 16).toUpperCase(Locale.ROOT),
            required(input.factoryModel(), "厂内型号", 64), variants, images);
    }

    static void validateImage(ImageInput image) {
        byte[] bytes = image.content();
        if (bytes == null || bytes.length < 12 || bytes.length > MAX_IMAGE_BYTES) {
            throw new BusinessException("单张图片必须有效且不超过 2 MB");
        }
        String mime = switch (HexFormat.of().formatHex(bytes, 0, 4)) {
            case "89504e47" -> (HexFormat.of().formatHex(bytes, 4, 8).equals("0d0a1a0a")) ? "image/png" : "";
            case "52494646" -> new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP") ? "image/webp" : "";
            default -> HexFormat.of().formatHex(bytes, 0, 3).equals("ffd8ff") ? "image/jpeg" : "";
        };
        if (mime.isEmpty() || !mime.equals(image.mediaType())) throw new BusinessException("图片格式须为 JPG、PNG 或 WebP");
    }

    private BigDecimal mm(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(100_000)) > 0
                || value.stripTrailingZeros().scale() > 2) {
            throw new BusinessException(label + "须为 0–100000 毫米之间的正数，最多两位小数");
        }
        return value.setScale(2);
    }

    private String required(String value, String label, int max) {
        String text = optional(value, label, max);
        if (text == null) throw new BusinessException(label + "不能为空");
        return text;
    }

    private String optional(String value, String label, int max) {
        if (value == null || value.isBlank()) return null;
        String text = value.strip();
        if (text.length() > max) throw new BusinessException(label + "长度超过 " + max);
        return text;
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("本地商品序列化失败", e);
        }
    }

    private List<Saved> savedResult(String value) {
        try {
            return json.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("读取商品保存回执失败", e);
        }
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
