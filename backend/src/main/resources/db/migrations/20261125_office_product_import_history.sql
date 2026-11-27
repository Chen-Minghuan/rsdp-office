alter table office_product_outbox
    add column if not exists import_workbook_id uuid,
    add column if not exists import_file_name varchar(255),
    add column if not exists import_file_bytes bigint;

create index if not exists idx_office_product_outbox_workbook
    on office_product_outbox(workspace_id, import_workbook_id, created_at, batch_id)
    where import_workbook_id is not null;
