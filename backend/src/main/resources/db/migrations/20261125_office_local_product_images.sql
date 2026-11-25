create index if not exists idx_office_product_image_position
    on office_product_image(workspace_id, product_id, position, image_id)
    where position is not null;
