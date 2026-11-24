alter table office_user add column if not exists is_system_admin boolean;
update office_user set is_system_admin = (upper(role) = 'ADMIN') where is_system_admin is null;
alter table office_user alter column is_system_admin set default false;
alter table office_user alter column is_system_admin set not null;
create unique index if not exists uk_office_one_admin on office_user (is_system_admin) where is_system_admin;

alter table office_workspace add column if not exists dealer_slot_no smallint check (dealer_slot_no between 1 and 20);
with unassigned as (
    select workspace_id, row_number() over (order by created_at, workspace_id) as position
    from office_workspace where dealer_slot_no is null
), available as (
    select slot, row_number() over (order by slot) as position from generate_series(1, 20) as slot
    where not exists (select 1 from office_workspace where dealer_slot_no = slot)
)
update office_workspace w set dealer_slot_no = available.slot
from unassigned join available using (position) where w.workspace_id = unassigned.workspace_id;
alter table office_workspace alter column dealer_slot_no set not null;
create unique index if not exists uk_office_dealer_slot on office_workspace(dealer_slot_no);

alter table office_project_member add column if not exists permission_flags integer check (permission_flags between 0 and 127);
-- 一次性保留旧项目成员已有的查看、编辑、方案导出及负责人管理权。新增成员由主号明确授予。
update office_project_member set permission_flags = 1 | 2 | 16 | case when role = 'LEADER' then 32 else 0 end
where permission_flags is null;
alter table office_project_member alter column permission_flags set default 0;
alter table office_project_member alter column permission_flags set not null;
