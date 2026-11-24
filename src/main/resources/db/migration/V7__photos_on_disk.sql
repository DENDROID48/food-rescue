-- Photo bytes now live in files in the uploads folder (FilePhotoStorage); the tables keep only
-- which photos exist and their image type.
alter table food_item_photo drop column data;
alter table product_template_photo drop column data;
