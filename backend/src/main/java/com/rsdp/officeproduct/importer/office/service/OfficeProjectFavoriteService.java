package com.rsdp.officeproduct.importer.office.service;

import com.rsdp.officeproduct.importer.common.AccessDeniedException;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.office.dto.OfficeProjectFavoriteDto;
import com.rsdp.officeproduct.importer.office.entity.OfficeProjectFavoriteItem;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProjectFavoriteItemMapper;
import com.rsdp.officeproduct.importer.service.ProductQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OfficeProjectFavoriteService {

    private final CurrentUserService currentUserService;
    private final OfficeProjectAccessService accessService;
    private final ProductQueryService productQueryService;
    private final OfficeProjectFavoriteItemMapper itemMapper;

    public List<String> listProjectFavoriteProductIds(String projectId) {
        String userId = requireProjectAccess(projectId);
        return itemMapper.findProductIds(projectId, userId);
    }

    public List<ProductListItemDto> listProjectFavoriteItems(String projectId) {
        String userId = requireProjectAccess(projectId);
        List<OfficeProjectFavoriteItem> items = itemMapper.findItems(projectId, userId);
        Map<String, ProductListItemDto> products = productQueryService.listProducts(false).stream()
            .collect(Collectors.toMap(ProductListItemDto::getRspuId, product -> product, (a, b) -> a));
        return items.stream()
            .sorted(Comparator.comparing(OfficeProjectFavoriteItem::getCreatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
            .map(item -> products.get(item.getRspuId()))
            .filter(Objects::nonNull)
            .toList();
    }

    public OfficeProjectFavoriteDto getProjectFavorite(String projectId, String rspuId) {
        String userId = requireProjectAccess(projectId);
        requireVisibleProduct(rspuId);
        return new OfficeProjectFavoriteDto(itemMapper.findOne(projectId, userId, rspuId) != null);
    }

    public OfficeProjectFavoriteDto saveProjectFavorite(String projectId, String rspuId, Boolean selected) {
        if (selected == null) {
            throw new BusinessException("收藏状态不能为空");
        }
        String userId = requireProjectAccess(projectId);
        requireVisibleProduct(rspuId);
        if (selected) {
            itemMapper.insertIgnore(projectId, userId, rspuId);
        } else {
            itemMapper.delete(projectId, userId, rspuId);
        }
        return new OfficeProjectFavoriteDto(selected);
    }

    private String requireProjectAccess(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            throw new BusinessException("项目不能为空");
        }
        String userId = requireUserId();
        accessService.requireMember(projectId);
        return userId;
    }

    private void requireVisibleProduct(String rspuId) {
        if (!StringUtils.hasText(rspuId)) {
            throw new BusinessException("商品不能为空");
        }
        productQueryService.getProduct(rspuId);
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
}
