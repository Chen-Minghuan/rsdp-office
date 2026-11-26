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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        var saved = bundles.list(userId()).stream().map(this::decode).toList();
        var referencedLocalIds = saved.stream().flatMap(bundle -> bundle.items().stream())
            .map(SavedItem::rspuId).filter(LocalProductReference::isLocal)
            .map(LocalProductReference::productId).collect(Collectors.toSet());
        if (referencedLocalIds.isEmpty()) return saved;
        Set<String> visibleIds = localProducts.catalogItems(referencedLocalIds).stream()
            .map(item -> item.id()).collect(Collectors.toSet());
        return saved.stream().map(bundle -> new Bundle(bundle.bundleId(), bundle.name(), bundle.revision(),
            bundle.items().stream().map(item -> {
                if (!LocalProductReference.isLocal(item.rspuId())
                        || visibleIds.contains(LocalProductReference.productId(item.rspuId()))) return item;
                return new SavedItem(item.rspuId(), item.variantId(), item.quantity(), null, null, null);
            }).toList())).toList();
    }

    public Bundle save(String bundleId, Save request) {
        validId(bundleId);
        if (request == null || request.name() == null || request.name().strip().isEmpty()
            || request.name().strip().length() > 100 || request.revision() < 0) throw new BusinessException("请填写有效的组合名称");
        String userId = userId();
        String name = request.name().strip();
        validateItems(request.items(), true);
        List<SavedItem> items = request.items().stream().map(this::resolveItem).toList();
        var row = new OfficeProductBundleMapper.Row(bundleId, userId, name, request.revision(), encode(items));
        int writes = request.revision() == 0 ? bundles.create(row) : bundles.update(row);
        if (writes != 1) throw new BusinessException("商品组合已变化，请重新读取后编辑");
        return new Bundle(bundleId, name, request.revision() + 1, items);
    }

    public void delete(String bundleId, int revision) {
        validId(bundleId);
        if (bundles.delete(bundleId, userId(), revision) != 1) throw new BusinessException("商品组合已变化或不存在");
    }

    private SavedItem resolveItem(Item item) {
        if (LocalProductReference.isLocal(item.rspuId())) {
            var detail = localProducts.detail(LocalProductReference.productId(item.rspuId()));
            var variant = detail.variants().stream().filter(row -> item.variantId().equals(row.id())).findFirst()
                .orElseThrow(() -> new BusinessException("组合中有商品变体不可用，请移除或替换后再保存"));
            String image = detail.images().isEmpty() ? null : LocalProductReference.imagePath(detail.images().get(0).id());
            return new SavedItem(item.rspuId(), item.variantId(), item.quantity(), detail.product().name(), variant.name(), image);
        }
        var product = office.currentProductVariant(item.rspuId(), item.variantId());
        if (product == null) throw new BusinessException("组合中有商品变体不可用，请移除或替换后再保存");
        return new SavedItem(item.rspuId(), item.variantId(), item.quantity(), product.getName(), product.getVariant(), product.getImageUrl());
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
