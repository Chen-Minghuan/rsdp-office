package com.rsdp.officeproduct.importer.office.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductBundleDtos.*;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProductBundleMapper;
import com.rsdp.officeproduct.importer.office.product.LocalProductReference;
import com.rsdp.officeproduct.importer.office.product.LocalProductService;
import com.rsdp.officeproduct.importer.office.product.LocalProductModels.Detail;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OfficeProductBundleService {
    private final OfficeProductBundleMapper bundles;
    private final CurrentUserService users;
    private final OfficeService office;
    private final LocalProductService localProducts;
    private final ObjectMapper json;

    public List<Bundle> list() {
        var catalog = new CatalogLookup();
        return bundles.list(userId()).stream().map(this::decode).map(bundle -> new Bundle(
            bundle.bundleId(), bundle.name(), bundle.revision(), bundle.items().stream().map(item ->
                resolveItem(new Item(item.rspuId(), item.variantId(), item.quantity()), item, catalog)).toList())).toList();
    }

    /** 一次列表/保存只查询被引用的商品，相同规格在多个组合中复用结果。 */
    private final class CatalogLookup {
        private final java.util.Map<String, Detail> local = new java.util.HashMap<>();
        private final java.util.Map<List<String>, OfficeProductDto> shared = new java.util.HashMap<>();

        Detail local(String productId) {
            return local.computeIfAbsent(productId, localProducts::detail);
        }

        OfficeProductDto shared(Item item) {
            var key = List.of(item.rspuId(), item.variantId());
            if (!shared.containsKey(key)) shared.put(key, office.currentProductVariant(item.rspuId(), item.variantId()));
            return shared.get(key);
        }
    }

    public Bundle save(String bundleId, Save request) {
        validId(bundleId);
        if (request == null || request.name() == null || request.name().strip().isEmpty()
            || request.name().strip().length() > 100 || request.revision() < 0) throw new BusinessException("请填写有效的组合名称");
        String userId = userId();
        String name = request.name().strip();
        validateItems(request.items(), true);
        var previousRow = request.revision() == 0 ? null : bundles.findById(bundleId, userId);
        if (request.revision() > 0 && (previousRow == null || previousRow.revision() != request.revision())) {
            throw new BusinessException("商品组合已变化，请重新读取后编辑");
        }
        var previousItems = previousRow == null ? java.util.Map.<String, SavedItem>of() : decode(previousRow).items().stream()
            .collect(Collectors.toMap(OfficeProductBundleService::itemKey, item -> item, (a, b) -> a));
        var catalog = new CatalogLookup();
        List<SavedItem> items = request.items().stream().map(item -> resolveItem(item, previousItems.get(itemKey(item)), catalog)).toList();
        var row = new OfficeProductBundleMapper.Row(bundleId, userId, name, request.revision(), encode(items));
        int writes = request.revision() == 0 ? bundles.create(row) : bundles.update(row);
        if (writes != 1) throw new BusinessException("商品组合已变化，请重新读取后编辑");
        return new Bundle(bundleId, name, request.revision() + 1, items);
    }

    public void delete(String bundleId, int revision) {
        validId(bundleId);
        if (bundles.delete(bundleId, userId(), revision) != 1) throw new BusinessException("商品组合已变化或不存在");
    }

    private static String itemKey(SavedItem item) {
        return item.rspuId() + "\n" + item.variantId();
    }

    private static String itemKey(Item item) {
        return item.rspuId() + "\n" + item.variantId();
    }

    private SavedItem resolveItem(Item item, SavedItem previous, CatalogLookup catalog) {
        if (LocalProductReference.isLocal(item.rspuId())) {
            Detail detail;
            try {
                // detail 按当前会话工作区查询；权限失败不能回退到其他工作区的旧展示信息。
                detail = catalog.local(LocalProductReference.productId(item.rspuId()));
            } catch (BusinessException | AccessDeniedException unavailable) {
                if (previous == null) throw unavailable;
                return new SavedItem(item.rspuId(), item.variantId(), item.quantity(), null, null, null, false);
            }
            var variant = detail.variants().stream().filter(row -> item.variantId().equals(row.id())).findFirst().orElse(null);
            if (variant == null && previous == null) throw unavailableItem();
            String image = detail.images().isEmpty() ? null : LocalProductReference.imagePath(detail.images().get(0).id());
            return new SavedItem(item.rspuId(), item.variantId(), item.quantity(), detail.product().name(),
                variant == null ? previous.variant() : variant.name(), image, variant != null);
        }
        OfficeProductDto product;
        try {
            product = catalog.shared(item);
        } catch (BusinessException unavailable) {
            if (previous == null) throw unavailable;
            product = null;
        }
        if (product == null) {
            if (previous == null) throw unavailableItem();
            return new SavedItem(item.rspuId(), item.variantId(), item.quantity(), previous.name(), previous.variant(), previous.imageUrl(), false);
        }
        return new SavedItem(item.rspuId(), item.variantId(), item.quantity(), product.getName(), product.getVariant(), product.getImageUrl(), true);
    }

    private BusinessException unavailableItem() {
        return new BusinessException("组合中有商品变体不可用，请移除或替换后再保存");
    }

    static void validateItems(List<Item> items, boolean allowEmpty) {
        if (items == null || (!allowEmpty && items.isEmpty()) || items.size() > 50) throw new BusinessException("商品组合明细数量无效");
        var keys = new HashSet<List<String>>();
        int total = 0;
        for (Item item : items) {
            if (item == null) throw new BusinessException("商品组合有无效商品");
            validId(LocalProductReference.isLocal(item.rspuId()) ? LocalProductReference.productId(item.rspuId()) : item.rspuId());
            validId(item.variantId());
            if (item.quantity() < 1 || item.quantity() > 200 || !keys.add(List.of(item.rspuId(), item.variantId()))) {
                throw new BusinessException("组合商品重复或数量无效");
            }
            total += item.quantity();
        }
        if (total > 200) throw new BusinessException("每次最多处理 200 件商品");
    }

    private static void validId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) throw new BusinessException("商品或组合 ID 无效");
    }

    private String userId() {
        var user = users.currentUser();
        if (user != null) return user.getUserId();
        if (users.authEnabled()) throw new AccessDeniedException("请先登录");
        return users.ensureLocalFavoriteUser();
    }

    private Bundle decode(OfficeProductBundleMapper.Row row) {
        try {
            return new Bundle(row.bundleId(), row.name(), row.revision(),
                json.readValue(row.itemsJson(), new TypeReference<List<SavedItem>>() {}));
        } catch (JsonProcessingException error) {
            throw new BusinessException("无法读取商品组合");
        }
    }

    private String encode(List<SavedItem> items) {
        try { return json.writeValueAsString(items); }
        catch (JsonProcessingException error) { throw new BusinessException("无法保存商品组合"); }
    }
}
