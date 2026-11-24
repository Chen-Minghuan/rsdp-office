package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.dto.OfficeComboDto;
import com.rsdp.officeproduct.importer.office.dto.OfficeComboItemDto;
import com.rsdp.officeproduct.importer.office.entity.OfficeCombo;
import com.rsdp.officeproduct.importer.office.entity.OfficeComboItem;
import com.rsdp.officeproduct.importer.office.mapper.OfficeComboItemMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeComboMapper;
import com.rsdp.officeproduct.importer.service.ProductQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OfficeComboService {

    static final int MAX_COMBOS_PER_USER = 10;
    static final int MAX_ITEMS_PER_COMBO = 5;

    private final CurrentUserService currentUserService;
    private final ProductQueryService productQueryService;
    private final OfficeComboMapper comboMapper;
    private final OfficeComboItemMapper itemMapper;

    public List<OfficeComboDto> listCombos() {
        String userId = requireUserId();
        List<OfficeCombo> combos = comboMapper.selectList(new QueryWrapper<OfficeCombo>()
            .eq("user_id", userId).orderByAsc("created_at", "combo_id"));
        Map<String, List<OfficeComboItem>> itemsByCombo = itemMapper.findByUserId(userId).stream()
            .collect(Collectors.groupingBy(OfficeComboItem::getComboId));
        Map<String, ProductListItemDto> products = productQueryService.listProducts(false).stream()
            .collect(Collectors.toMap(ProductListItemDto::getRspuId, product -> product, (a, b) -> a));
        return combos.stream().map(combo -> new OfficeComboDto(combo.getComboId(), combo.getName(),
            itemsByCombo.getOrDefault(combo.getComboId(), List.of()).stream()
                .map(item -> toItemDto(item, products.get(item.getRspuId())))
                .toList())).toList();
    }

    @Transactional
    public OfficeComboDto createCombo(String name) {
        String userId = requireUserId();
        String normalizedName = normalizeName(name);
        // 数量校验与插入必须在同一用户锁内，避免并发创建突破 10 个上限。
        comboMapper.lockUser(userId);
        Long count = comboMapper.selectCount(new QueryWrapper<OfficeCombo>().eq("user_id", userId));
        if (count != null && count >= MAX_COMBOS_PER_USER) {
            throw new BusinessException("最多创建" + MAX_COMBOS_PER_USER + "个组合");
        }
        OfficeCombo combo = new OfficeCombo();
        combo.setComboId(newComboId());
        combo.setUserId(userId);
        combo.setName(normalizedName);
        combo.setCreatedAt(LocalDateTime.now());
        combo.setUpdatedAt(LocalDateTime.now());
        try {
            comboMapper.insert(combo);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException("组合名称已存在", exception);
        }
        return new OfficeComboDto(combo.getComboId(), combo.getName(), List.of());
    }

    @Transactional
    public void renameCombo(String comboId, String name) {
        String userId = requireUserId();
        OfficeCombo combo = requireOwnedCombo(userId, comboId);
        combo.setName(normalizeName(name));
        combo.setUpdatedAt(LocalDateTime.now());
        try {
            comboMapper.updateById(combo);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException("组合名称已存在", exception);
        }
    }

    @Transactional
    public void saveComboItems(String comboId, List<String> rspuIds) {
        String userId = requireUserId();
        requireOwnedCombo(userId, comboId);
        if (rspuIds == null) {
            throw new BusinessException("组合商品不能为空");
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String rspuId : rspuIds) {
            if (!StringUtils.hasText(rspuId)) {
                throw new BusinessException("商品不能为空");
            }
            selected.add(rspuId.trim());
        }
        if (selected.size() > MAX_ITEMS_PER_COMBO) {
            throw new BusinessException("每个组合最多" + MAX_ITEMS_PER_COMBO + "个商品");
        }
        List<String> requested = new ArrayList<>(selected);
        for (String rspuId : requested) {
            productQueryService.getProduct(rspuId);
        }
        itemMapper.lockCombo(userId, comboId);
        itemMapper.deleteByComboId(comboId);
        for (int index = 0; index < requested.size(); index++) {
            OfficeComboItem item = new OfficeComboItem();
            item.setComboId(comboId);
            item.setRspuId(requested.get(index));
            item.setSortOrder(index);
            itemMapper.insert(item);
        }
    }

    @Transactional
    public void deleteCombo(String comboId) {
        String userId = requireUserId();
        requireOwnedCombo(userId, comboId);
        itemMapper.deleteByComboId(comboId);
        comboMapper.deleteById(comboId);
    }

    private OfficeCombo requireOwnedCombo(String userId, String comboId) {
        if (!StringUtils.hasText(comboId)) {
            throw new BusinessException("组合不能为空");
        }
        OfficeCombo combo = comboMapper.selectById(comboId);
        if (combo == null) {
            throw new BusinessException("组合不存在");
        }
        if (!userId.equals(combo.getUserId())) {
            throw new AccessDeniedException("无权操作其他用户的组合");
        }
        return combo;
    }

    private String normalizeName(String name) {
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isEmpty()) {
            throw new BusinessException("组合名称不能为空");
        }
        if (normalizedName.length() > 50) {
            throw new BusinessException("组合名称不能超过50个字符");
        }
        return normalizedName;
    }

    private OfficeComboItemDto toItemDto(OfficeComboItem item, ProductListItemDto product) {
        boolean available = product != null && "active".equalsIgnoreCase(product.getStatus());
        return new OfficeComboItemDto(item.getRspuId(),
            product == null ? null : product.getProductName(),
            product == null ? null : product.getPrimaryImageUrl(),
            product == null ? null : product.getRetailPrice(),
            available);
    }

    private String requireUserId() {
        // 认证关闭的本地开发模式没有会话用户，使用固定本地用户保证组合读写一致。
        if (!currentUserService.authEnabled()) {
            return currentUserService.ensureLocalFavoriteUser();
        }
        OfficeUser user = currentUserService.currentUser();
        if (user == null || !StringUtils.hasText(user.getUserId())) {
            throw new AccessDeniedException("请先登录");
        }
        return user.getUserId();
    }

    private String newComboId() {
        return "CB-" + UUID.randomUUID().toString().replace("-", "");
    }
}
