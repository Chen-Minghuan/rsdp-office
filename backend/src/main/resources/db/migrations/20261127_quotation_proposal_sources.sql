alter table office_quotation add column if not exists proposal_confirmations_json jsonb not null default '{}'::jsonb;
