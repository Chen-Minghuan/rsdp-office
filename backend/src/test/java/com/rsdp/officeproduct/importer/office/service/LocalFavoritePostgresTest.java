package com.rsdp.officeproduct.importer.office.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.rsdp.officeproduct.importer.auth.AuthService;
import com.rsdp.officeproduct.importer.config.SchemaInitializer;
import com.rsdp.officeproduct.importer.dto.response.ProductDetailDto;
import com.rsdp.officeproduct.importer.mapper.OfficeUserMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeFavoriteFolderMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeFavoriteItemMapper;
import com.rsdp.officeproduct.importer.office.mapper.OfficeProjectFavoriteItemMapper;
import com.rsdp.officeproduct.importer.service.ProductQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

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
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(scoped);
            factory.setConfiguration(configuration);
            factory.afterPropertiesSet();
            try (var session = factory.getObject().openSession(true)) {
                var users = session.getMapper(OfficeUserMapper.class);
                var currentUser = new CurrentUserService(mock(AuthService.class), users);
                var products = mock(ProductQueryService.class);
                when(products.getProduct("P-1")).thenReturn(new ProductDetailDto());
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
            }
        } finally {
            try (var connection = database.getConnection(); var statement = connection.createStatement()) {
                statement.execute("drop schema " + schema + " cascade");
            }
        }
    }
}
