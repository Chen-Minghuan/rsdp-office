-- Existing databases need these variant-level source fields without changing stored products.
alter table if exists rspu_variant add column if not exists package_count integer;
alter table if exists rspu_variant add column if not exists package_volume_cbm numeric(18,9);
alter table if exists rspu_variant add column if not exists gross_weight numeric(14,3);
