-- Customers can stop being asked to get verified for free items, and turn the offer back on in Settings.
alter table radar_profile add column free_items_offer boolean default true not null;
