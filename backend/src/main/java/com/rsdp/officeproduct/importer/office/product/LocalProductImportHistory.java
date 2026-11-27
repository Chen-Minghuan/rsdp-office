package com.rsdp.officeproduct.importer.office.product;

import com.rsdp.officeproduct.importer.common.BusinessException;
import com.rsdp.officeproduct.importer.office.service.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.rsdp.officeproduct.importer.office.product.LocalProductModels.Saved;

@Service
@RequiredArgsConstructor
public class LocalProductImportHistory {
    private static final int PAGE_SIZE = 20;
    private static final String SCOPE = """
        o.workspace_id = :workspace and o.import_workbook_id is not null
        and (:owner or o.actor_id = :actor)
        """;
    private static final String SUMMARY = """
        select o.import_workbook_id, o.import_file_name, o.import_file_bytes, u.display_name,
            min(o.created_at) as first_saved_at, count(*) as batches,
            sum(jsonb_array_length(o.result)) as products
        from office_product_outbox o join office_user u on u.user_id = o.actor_id
        where
        """ + SCOPE;
    private static final String GROUP_BY = """
         group by o.import_workbook_id, o.import_file_name, o.import_file_bytes, o.actor_id, u.display_name
        """;
    private static final RowMapper<Entry> ENTRY = (row, index) -> new Entry(
        row.getObject("import_workbook_id", UUID.class), row.getString("import_file_name"),
        row.getLong("import_file_bytes"), row.getString("display_name"), row.getObject("first_saved_at", OffsetDateTime.class),
        row.getLong("batches"), row.getLong("products"));

    private final CurrentUserService users;
    private final NamedParameterJdbcTemplate jdbc;

    public record Entry(UUID id, String fileName, long fileBytes, String actorName, OffsetDateTime createdAt,
                        long batchCount, long productCount) {}
    public record Page(boolean canWrite, boolean hasMore, List<Entry> records) {}
    public record Detail(Entry record, List<Saved> products) {}

    public Page list(int page, String search) {
        if (page < 1 || search.length() > 120) throw new BusinessException("导入记录查询参数无效");
        var user = users.currentUser();
        if (user == null || user.getWorkspaceId() == null) return new Page(false, false, List.of());
        var parameters = scope(user.getWorkspaceId(), user.getUserId(), "DEALER".equals(user.getRole()))
            .addValue("search", search.strip()).addValue("limit", PAGE_SIZE + 1).addValue("offset", (page - 1L) * PAGE_SIZE);
        var rows = jdbc.query(SUMMARY + " and (:search = '' or strpos(lower(o.import_file_name), lower(:search)) > 0)"
            + GROUP_BY + " order by first_saved_at desc, o.import_workbook_id limit :limit offset :offset", parameters, ENTRY);
        return new Page(LocalProductService.canWrite(user), rows.size() > PAGE_SIZE, rows.stream().limit(PAGE_SIZE).toList());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(UUID id) {
        var user = users.currentUser();
        if (user == null || user.getWorkspaceId() == null) throw new BusinessException(404, "导入记录不存在");
        var parameters = scope(user.getWorkspaceId(), user.getUserId(), "DEALER".equals(user.getRole())).addValue("id", id);
        var record = jdbc.query(SUMMARY + " and o.import_workbook_id = :id" + GROUP_BY, parameters, ENTRY)
            .stream().findFirst().orElseThrow(() -> new BusinessException(404, "导入记录不存在"));
        var products = jdbc.query("""
            select item.value->>'id', item.value->>'name', (item.value->>'revision')::int
            from office_product_outbox o cross join lateral jsonb_array_elements(o.result) with ordinality item(value, position)
            where
            """ + SCOPE + " and o.import_workbook_id = :id order by o.created_at, o.batch_id, item.position",
            parameters, (row, index) -> new Saved(row.getString(1), row.getInt(3), row.getString(2)));
        return new Detail(record, products);
    }

    private MapSqlParameterSource scope(String workspace, String actor, boolean owner) {
        return new MapSqlParameterSource("workspace", workspace).addValue("actor", actor).addValue("owner", owner);
    }
}
