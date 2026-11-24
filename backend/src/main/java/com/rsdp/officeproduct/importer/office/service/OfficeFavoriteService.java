package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.dto.OfficeFavoriteFolderDto;
import com.rsdp.officeproduct.importer.office.entity.OfficeFavoriteFolder;
import com.rsdp.officeproduct.importer.office.entity.OfficeFavoriteItem;
import com.rsdp.officeproduct.importer.office.mapper.OfficeFavoriteFolderMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeFavoriteItemMapper;
import com.rsdp.officeproduct.importer.service.ProductQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OfficeFavoriteService {

    private final CurrentUserService currentUserService;
    private final ProductQueryService productQueryService;
    private final OfficeFavoriteFolderMapper folderMapper;
    private final OfficeFavoriteItemMapper itemMapper;

    @Transactional
    public List<OfficeFavoriteFolderDto> listFolders(String rspuId) {
        String userId = requireUserId();
        ensureDefaultFolder(userId);
        List<OfficeFavoriteFolder> folders = folderMapper.selectList(new QueryWrapper<OfficeFavoriteFolder>()
            .eq("user_id", userId)
            .orderByDesc("is_default")
            .orderByAsc("created_at", "folder_id"));
        List<OfficeFavoriteItem> items = itemMapper.findByUserId(userId);
        Map<String, Long> counts = items.stream().collect(Collectors.groupingBy(
            OfficeFavoriteItem::getFolderId, Collectors.counting()));
        Set<String> selectedFolderIds = StringUtils.hasText(rspuId)
            ? items.stream().filter(item -> rspuId.equals(item.getRspuId()))
                .map(OfficeFavoriteItem::getFolderId).collect(Collectors.toSet())
            : Set.of();
        return folders.stream().map(folder -> toDto(folder, counts, selectedFolderIds)).toList();
    }

    @Transactional
    public OfficeFavoriteFolderDto createFolder(String name) {
        String userId = requireUserId();
        ensureDefaultFolder(userId);
        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isEmpty()) {
            throw new BusinessException("收藏夹名称不能为空");
        }
        if (normalizedName.length() > 50) {
            throw new BusinessException("收藏夹名称不能超过50个字符");
        }
        OfficeFavoriteFolder folder = new OfficeFavoriteFolder();
        folder.setFolderId(newFolderId());
        folder.setUserId(userId);
        folder.setName(normalizedName);
        folder.setDefaultFolder(false);
        folder.setCreatedAt(LocalDateTime.now());
        folder.setUpdatedAt(LocalDateTime.now());
        try {
            folderMapper.insert(folder);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException("收藏夹名称已存在", exception);
        }
        return new OfficeFavoriteFolderDto(folder.getFolderId(), folder.getName(), 0, false, false);
    }

    @Transactional
    public void saveProductFolders(String rspuId, List<String> folderIds) {
        String userId = requireUserId();
        if (!StringUtils.hasText(rspuId)) {
            throw new BusinessException("商品不能为空");
        }
        if (folderIds == null) {
            throw new BusinessException("收藏夹选择不能为空");
        }
        productQueryService.getProduct(rspuId);
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String folderId : folderIds) {
            if (!StringUtils.hasText(folderId)) {
                throw new BusinessException("收藏夹不能为空");
            }
            selected.add(folderId);
        }
        List<String> requested = new ArrayList<>(selected);
        List<OfficeFavoriteFolder> requestedFolders = requested.isEmpty()
            ? List.of() : folderMapper.selectBatchIds(requested);
        if (requestedFolders.size() != requested.size()) {
            throw new BusinessException("收藏夹不存在");
        }
        if (requestedFolders.stream().anyMatch(folder -> !userId.equals(folder.getUserId()))) {
            throw new AccessDeniedException("无权使用其他用户的收藏夹");
        }

        itemMapper.lockProduct(userId, rspuId);
        List<OfficeFavoriteItem> currentItems = itemMapper.findByUserId(userId).stream()
            .filter(item -> rspuId.equals(item.getRspuId())).toList();
        Set<String> currentFolderIds = currentItems.stream()
            .map(OfficeFavoriteItem::getFolderId).collect(Collectors.toSet());
        for (OfficeFavoriteItem item : currentItems) {
            if (!selected.contains(item.getFolderId())) {
                itemMapper.delete(item.getFolderId(), rspuId);
            }
        }
        for (String folderId : requested) {
            if (!currentFolderIds.contains(folderId)) {
                itemMapper.insertIgnore(folderId, rspuId);
            }
        }
    }

    public List<String> listFavoriteProductIds() {
        String userId = requireUserId();
        return itemMapper.findByUserId(userId).stream()
            .map(OfficeFavoriteItem::getRspuId)
            .distinct()
            .sorted()
            .toList();
    }

    public List<ProductListItemDto> listFavoriteItems(String folderId) {
        String userId = requireUserId();
        if (StringUtils.hasText(folderId)) {
            OfficeFavoriteFolder folder = folderMapper.selectById(folderId);
            if (folder == null) {
                throw new BusinessException("收藏夹不存在");
            }
            if (!userId.equals(folder.getUserId())) {
                throw new AccessDeniedException("无权查看其他用户的收藏夹");
            }
        }
        Map<String, LocalDateTime> latestByProduct = new HashMap<>();
        for (OfficeFavoriteItem item : itemMapper.findByUserId(userId)) {
            if (StringUtils.hasText(folderId) && !folderId.equals(item.getFolderId())) {
                continue;
            }
            latestByProduct.merge(item.getRspuId(), item.getCreatedAt(),
                (current, next) -> next != null && (current == null || next.isAfter(current)) ? next : current);
        }
        Map<String, ProductListItemDto> products = productQueryService.listProducts(false).stream()
            .collect(Collectors.toMap(ProductListItemDto::getRspuId, product -> product, (a, b) -> a));
        return latestByProduct.entrySet().stream()
            .sorted(Map.Entry.<String, LocalDateTime>comparingByValue(
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
            .map(entry -> products.get(entry.getKey()))
            .filter(Objects::nonNull)
            .toList();
    }

    @Transactional
    public void deleteFolder(String folderId) {
        String userId = requireUserId();
        if (!StringUtils.hasText(folderId)) {
            throw new BusinessException("收藏夹不能为空");
        }
        OfficeFavoriteFolder folder = folderMapper.selectById(folderId);
        if (folder == null) {
            throw new BusinessException("收藏夹不存在");
        }
        if (!userId.equals(folder.getUserId())) {
            throw new AccessDeniedException("无权删除其他用户的收藏夹");
        }
        if (Boolean.TRUE.equals(folder.getDefaultFolder())) {
            throw new BusinessException("默认收藏夹不能删除");
        }
        itemMapper.deleteByFolderId(folderId);
        folderMapper.deleteById(folderId);
    }

    private OfficeFavoriteFolder ensureDefaultFolder(String userId) {
        QueryWrapper<OfficeFavoriteFolder> query = new QueryWrapper<OfficeFavoriteFolder>()
            .eq("user_id", userId).eq("is_default", true).last("limit 1");
        OfficeFavoriteFolder folder = folderMapper.selectOne(query);
        if (folder == null) {
            folderMapper.insertDefaultFolder(newFolderId(), userId);
            folder = folderMapper.selectOne(query);
        }
        if (folder == null) {
            throw new BusinessException("默认收藏夹初始化失败");
        }
        return folder;
    }

    private OfficeFavoriteFolderDto toDto(OfficeFavoriteFolder folder, Map<String, Long> counts,
                                          Set<String> selectedFolderIds) {
        return new OfficeFavoriteFolderDto(folder.getFolderId(), folder.getName(),
            counts.getOrDefault(folder.getFolderId(), 0L), Boolean.TRUE.equals(folder.getDefaultFolder()),
            selectedFolderIds.contains(folder.getFolderId()));
    }

    private String requireUserId() {
        // 认证关闭的本地开发模式没有会话用户，使用固定本地用户保证收藏读写一致。
        if (!currentUserService.authEnabled()) {
            return "LOCAL-DEV";
        }
        OfficeUser user = currentUserService.currentUser();
        if (user == null || !StringUtils.hasText(user.getUserId())) {
            throw new AccessDeniedException("请先登录");
        }
        return user.getUserId();
    }

    private String newFolderId() {
        return "FF-" + UUID.randomUUID().toString().replace("-", "");
    }
}
