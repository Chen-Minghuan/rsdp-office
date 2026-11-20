-- 已落库报价保留原总额与历史明细，费用只迁移一次。
do $$
begin
    if exists (select 1 from pg_attribute where attrelid = 'office_quotation'::regclass
               and attname = 'snapshot_json' and not attisdropped) then
        alter table office_quotation add column fees_json jsonb;

        update office_quotation
        set fees_json = case
            when jsonb_typeof(snapshot_json -> 'fees') = 'array' then snapshot_json -> 'fees'
            else jsonb_build_array(
                jsonb_build_object('name', '服务费', 'amount', service_fee),
                jsonb_build_object('name', '物流搬运安装费', 'amount', logistics_fee))
            end;

        alter table office_quotation
            alter column fees_json set default '[]'::jsonb,
            alter column fees_json set not null,
            add constraint ck_office_quotation_fees_array check (jsonb_typeof(fees_json) = 'array'),
            drop column snapshot_json,
            drop column service_fee,
            drop column logistics_fee,
            drop column goods_total,
            drop column line_count,
            drop column missing_price_lines;
    end if;

    if exists (select 1 from pg_attribute where attrelid = 'office_quotation_line'::regclass
               and attname = 'sort_order' and not attisdropped) then
        alter table office_quotation_line rename column sort_order to line_no;
        alter table office_quotation_line
            drop constraint office_quotation_line_pkey,
            drop column line_id,
            drop column subtotal,
            alter column line_no drop default,
            add primary key (quotation_id, line_no);
    end if;
end $$;

drop index if exists idx_office_quotation_project;
drop index if exists idx_office_quotation_line_quotation;
