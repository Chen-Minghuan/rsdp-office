-- Superseded by actual variant dimensions, category, color, price and image assets.
-- Existing values in these columns are discarded when the application next starts.
alter table if exists rspu_master drop column if exists positioning_label;
alter table if exists rspu_master drop column if exists product_level;
alter table if exists rspu_master drop column if exists warranty_years;
alter table if exists rspu_master drop column if exists reference_price_band;
alter table if exists rspu_master drop column if exists six_dim_tags;
alter table if exists rspu_master drop column if exists material_tags;
alter table if exists rspu_master drop column if exists scene_tags;
alter table if exists rspu_master drop column if exists key_specs;
alter table if exists rspu_variant drop column if exists product_level;
alter table if exists rspu_variant drop column if exists reference_price_band;
