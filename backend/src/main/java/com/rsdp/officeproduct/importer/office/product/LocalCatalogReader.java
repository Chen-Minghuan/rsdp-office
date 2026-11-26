package com.rsdp.officeproduct.importer.office.product;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

import static com.rsdp.officeproduct.importer.office.product.LocalProductModels.CatalogItem;

@Repository
@RequiredArgsConstructor
public class LocalCatalogReader {
    private static final String CATALOG_ROWS = """
        select p.product_id, p.revision, p.name, p.category_code, p.factory_code, p.factory_model,
            (select i.image_id from office_product_image i
             where i.workspace_id = p.workspace_id and i.product_id = p.product_id and i.position is not null
             order by i.position, i.image_id limit 1) as primary_image_id
        from office_product p where p.workspace_id = :workspace
        """;
    private static final RowMapper<CatalogItem> CATALOG_ITEM = (r, i) ->
        new CatalogItem(r.getString("product_id"), r.getInt("revision"), r.getString("name"),
            r.getString("category_code"), r.getString("factory_code"), r.getString("factory_model"),
            r.getString("primary_image_id"));
    private static final String MATCH = """
        (:search = '' or strpos(lower(p.name), lower(:search)) > 0
                      or strpos(lower(p.factory_model), lower(:search)) > 0)
        and (:category = '' or p.category_code = :category)
        and (:color = '' or exists (
            select 1 from office_product_variant v where v.workspace_id = p.workspace_id
                and v.product_id = p.product_id and v.active and btrim(v.color_text) = :color))
        """;
    private final NamedParameterJdbcTemplate jdbc;

    public record Counts(long total, long matching) {}

    public Counts counts(String workspace, String search, String category, String color) {
        return jdbc.queryForObject("select count(*), count(*) filter (where " + MATCH + ")"
                + " from office_product p where p.workspace_id = :workspace",
            parameters(workspace, search, category, color), (r, i) -> new Counts(r.getLong(1), r.getLong(2)));
    }

    public List<CatalogItem> rows(String workspace, String search, String category, String color, long offset, int limit) {
        return jdbc.query(CATALOG_ROWS + " and " + MATCH + " order by p.updated_at desc, p.product_id limit :limit offset :offset",
            parameters(workspace, search, category, color).addValue("offset", offset).addValue("limit", limit),
            CATALOG_ITEM);
    }

    public List<CatalogItem> findByIds(String workspace, Collection<String> ids) {
        return jdbc.query(CATALOG_ROWS + " and p.product_id in (:ids)",
            new MapSqlParameterSource("workspace", workspace).addValue("ids", ids), CATALOG_ITEM);
    }

    public List<String> categories(String workspace) {
        return jdbc.queryForList("select distinct category_code from office_product where workspace_id = :workspace",
            new MapSqlParameterSource("workspace", workspace), String.class);
    }

    public List<String> colors(String workspace) {
        return jdbc.queryForList("""
            select distinct btrim(color_text) from office_product_variant
            where workspace_id = :workspace and active and btrim(color_text) <> ''
            """, new MapSqlParameterSource("workspace", workspace), String.class);
    }

    private MapSqlParameterSource parameters(String workspace, String search, String category, String color) {
        return new MapSqlParameterSource("workspace", workspace).addValue("search", search)
            .addValue("category", category).addValue("color", color);
    }
}
