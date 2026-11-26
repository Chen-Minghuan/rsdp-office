package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.officeproduct.importer.auth.AuthService;
import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.config.SchemaInitializer;
import com.rsdp.officeproduct.importer.dto.response.ProductListItemDto;
import com.rsdp.officeproduct.importer.entity.OfficeUser;
import com.rsdp.officeproduct.importer.mapper.OfficeUserMapper;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductBundleDtos.Item;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductBundleDtos.Save;
import com.rsdp.officeproduct.importer.office.dto.OfficeProductDto;
import com.rsdp.officeproduct.importer.office.mapper.OfficeFavoriteFolderMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeFavoriteItemMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProductBundleMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProjectFavoriteItemMapper;
import com.rsdp.officeproduct.importer.office.product.LocalCatalogReader;
import com.rsdp.officeproduct.importer.office.product.LocalProductService;
import com.rsdp.officeproduct.importer.office.product.WorkbookPreviewCache;
import com.rsdp.officeproduct.importer.service.ProductQueryService;
import com.rsdp.officeproduct.importer.service.CatalogProductFilterService;
import com.rsdp.officeproduct.importer.service.RsdpCatalogGateway;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 显式提供临时 PostgreSQL 时运行；每次使用独立 schema 并在结束时删除。 */
@EnabledIfEnvironmentVariable(named = "RSDP_TEST_POSTGRES_URL", matches = ".+")
class LocalFavoritePostgresTest {
    @Test
    void localPersonalAndProjectFavoritesPersistWithRealUserForeignKeys() throws Exception {
        String url = System.getenv("RSDP_TEST_POSTGRES_URL");
        String username = System.getenv().getOrDefault("RSDP_TEST_POSTGRES_USER", "postgres");
        String password = System.getenv().getOrDefault("RSDP_TEST_POSTGRES_PASSWORD", "");
        var database = new DriverManagerDataSource(url, username, password);
        String schema = "favorite_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        try {
            var scoped = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, username, password);
            new SchemaInitializer(scoped).init();
            try (var connection = scoped.getConnection(); var statement = connection.createStatement()) {
                statement.execute("insert into office_design_project(project_id, name) values ('PROJECT', '验证项目')");
            }
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(OfficeUserMapper.class);
            configuration.addMapper(OfficeFavoriteFolderMapper.class);
            configuration.addMapper(OfficeFavoriteItemMapper.class);
            configuration.addMapper(OfficeProjectFavoriteItemMapper.class);
            configuration.addMapper(OfficeProductBundleMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(scoped);
            factory.setConfiguration(configuration);
            factory.afterPropertiesSet();
            try (var session = factory.getObject().openSession(true)) {
                var users = session.getMapper(OfficeUserMapper.class);
                var currentUser = new CurrentUserService(mock(AuthService.class), users);
                var products = mock(ProductQueryService.class);
                var personal = new OfficeFavoriteService(currentUser, products,
                    session.getMapper(OfficeFavoriteFolderMapper.class), session.getMapper(OfficeFavoriteItemMapper.class));
                var folders = personal.listFolders(null);
                assertEquals(1, folders.size());
                assertEquals(folders.get(0).getFolderId(), personal.listFolders(null).get(0).getFolderId());
                assertEquals("disabled", users.selectById("LOCAL-DEV").getStatus());
                assertEquals("USER", users.selectById("LOCAL-DEV").getRole());
                assertNull(users.selectById("LOCAL-DEV").getWorkspaceId());

                personal.saveProductFolders("P-1", List.of(folders.get(0).getFolderId()));
                assertEquals(List.of("P-1"), personal.listFavoriteProductIds());
                var project = new OfficeProjectFavoriteService(currentUser, mock(OfficeProjectAccessService.class),
                    products, session.getMapper(OfficeProjectFavoriteItemMapper.class));
                assertTrue(project.saveProjectFavorite("PROJECT", "P-1", true).selected());
                assertEquals(List.of("P-1"), project.listProjectFavoriteProductIds("PROJECT"));

                personal.saveProductFolders("P-1", List.of());
                project.saveProjectFavorite("PROJECT", "P-1", false);
                assertTrue(personal.listFavoriteProductIds().isEmpty());
                assertTrue(project.listProjectFavoriteProductIds("PROJECT").isEmpty());
                verifyWorkspaceCollections(scoped, session, users.selectById("LOCAL-DEV"));
            }
        } finally {
            try (var connection = database.getConnection(); var statement = connection.createStatement()) {
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }

    private void verifyWorkspaceCollections(DriverManagerDataSource database, SqlSession session, OfficeUser member) {
        var jdbc = new JdbcTemplate(database);
        jdbc.update("""
            insert into office_user(user_id, username, password_hash, display_name, role)
            values ('OTHER-OWNER', 'other-owner', 'unused', '其他工作区', 'DEALER')
            """);
        jdbc.update("""
            insert into office_workspace(workspace_id, name, owner_user_id, dealer_slot_no)
            values ('WORKSPACE-A', '本地商品', 'LOCAL-DEV', 1), ('WORKSPACE-B', '其他工作区', 'OTHER-OWNER', 2)
            """);
        jdbc.update("""
            insert into office_product(product_id, workspace_id, name, category_code, factory_code,
                factory_model, revision, created_by, updated_by)
            values ('TEA-TABLE', 'WORKSPACE-A', '本地茶几', 'TB', '', 'MODEL-A', 1, 'LOCAL-DEV', 'LOCAL-DEV')
            """);
        jdbc.update("""
            insert into office_product_variant(variant_id, workspace_id, product_id, display_name, width_mm, depth_mm, position, active)
            values ('STANDARD', 'WORKSPACE-A', 'TEA-TABLE', '1.2 米', 1200, 600, 0, true),
                   ('INACTIVE', 'WORKSPACE-A', 'TEA-TABLE', '旧款', 1000, 600, 1, false)
            """);

        member.setWorkspaceId("WORKSPACE-A");
        var currentUser = mock(CurrentUserService.class);
        when(currentUser.authEnabled()).thenReturn(true);
        when(currentUser.currentUser()).thenReturn(member);
        var access = mock(OfficeCatalogAccess.class);
        when(access.capabilities()).thenReturn(new OfficeCatalogAccess.Capabilities(false));
        var json = new ObjectMapper();
        var local = new LocalProductService(jdbc, json, currentUser, access, mock(WorkbookPreviewCache.class),
            new LocalCatalogReader(new NamedParameterJdbcTemplate(jdbc)));
        var shared = mock(SharedCatalogService.class);
        when(shared.resolveViewMode()).thenReturn("none");
        var gateway = mock(RsdpCatalogGateway.class);
        var products = new ProductQueryService(gateway, mock(CatalogProductFilterService.class), shared, access, local);
        var personal = new OfficeFavoriteService(currentUser, products,
            session.getMapper(OfficeFavoriteFolderMapper.class), session.getMapper(OfficeFavoriteItemMapper.class));
        var project = new OfficeProjectFavoriteService(currentUser, mock(OfficeProjectAccessService.class),
            products, session.getMapper(OfficeProjectFavoriteItemMapper.class));

        String localReference = "OFFICE-LOCAL-TEA-TABLE";
        String folder = personal.listFolders(null).get(0).getFolderId();
        personal.saveProductFolders(localReference, List.of(folder));
        assertTrue(project.saveProjectFavorite("PROJECT", localReference, true).selected());
        assertEquals("本地茶几", personal.listFavoriteItems(folder).get(0).getProductName());
        assertEquals(localReference, project.listProjectFavoriteItems("PROJECT").get(0).getRspuId());

        var office = mock(OfficeService.class);
        var bundles = new OfficeProductBundleService(session.getMapper(OfficeProductBundleMapper.class),
            currentUser, office, local, json);
        var localItem = new Item(localReference, "STANDARD", 1);
        var saved = bundles.save("BUNDLE", new Save("茶几组合", 0, List.of(localItem)));
        assertEquals("1.2 米", saved.items().get(0).variant());
        assertEquals(saved, bundles.list().get(0));
        assertThrows(BusinessException.class, () -> bundles.save("BUNDLE",
            new Save("茶几组合", 1, List.of(new Item(localReference, "INACTIVE", 1)))));
        verifyNoInteractions(gateway, office);

        when(shared.resolveViewMode()).thenReturn("full");
        var publicItem = new ProductListItemDto();
        publicItem.setRspuId("RSPU-PUBLIC");
        when(gateway.inView("RSPU-PUBLIC", "full")).thenReturn(true);
        when(gateway.listProducts("full")).thenReturn(List.of(publicItem));
        personal.saveProductFolders("RSPU-PUBLIC", List.of(folder));
        assertEquals(2, personal.listFavoriteItems(folder).size());
        when(gateway.listProducts("full")).thenThrow(new BusinessException("公共库暂不可用"));
        assertEquals(localReference, personal.listFavoriteItems(folder).get(0).getRspuId());

        var publicVariant = new OfficeProductDto();
        publicVariant.setName("公共商品");
        when(office.currentProductVariant("RSPU-PUBLIC", "PUBLIC-VARIANT")).thenReturn(publicVariant);
        var mixed = bundles.save("BUNDLE", new Save("混合组合", 1,
            List.of(localItem, new Item("RSPU-PUBLIC", "PUBLIC-VARIANT", 1))));
        assertEquals(mixed, bundles.list().get(0));

        member.setWorkspaceId("WORKSPACE-B");
        when(shared.resolveViewMode()).thenReturn("none");
        assertTrue(personal.listFavoriteItems(folder).isEmpty());
        assertTrue(project.listProjectFavoriteItems("PROJECT").isEmpty());
        assertThrows(BusinessException.class, () -> personal.saveProductFolders(localReference, List.of(folder)));
        assertThrows(BusinessException.class, () -> project.saveProjectFavorite("PROJECT", localReference, true));
        assertThrows(BusinessException.class, () -> bundles.save("BUNDLE", new Save("其他工作区", 2, List.of(localItem))));
        assertNull(bundles.list().get(0).items().get(0).name());
        assertNull(bundles.list().get(0).items().get(0).variant());
    }
}
