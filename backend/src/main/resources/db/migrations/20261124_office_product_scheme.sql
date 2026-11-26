alter table office_canvas add column if not exists scheme_token varchar(64);
alter table office_render_preview add column if not exists confirmation_id varchar(64);
alter table office_render_preview drop constraint if exists office_render_preview_purpose_check;
alter table office_render_preview add constraint office_render_preview_purpose_check check (
    (purpose = 'CONCEPT_PREVIEW' and confirmation_id is null)
    or (purpose = 'CONFIRMED_SCHEME' and confirmation_id is not null)
);
