-- Smart Analytics: optional weights, the quantities an offer was published with, pickup reminders.

-- Approximate weight of one unit in grams, as the shop estimates it; null when unknown.
alter table product_template add column unit_weight_grams int;
alter table food_item add column unit_weight_grams int;

-- Units the offer was published with (adjusted when the owner changes the shelf count). Older
-- offers have null; analytics then reconstructs the number from their orders.
alter table food_item add column quantity_published int;
alter table food_item add column free_published int;

-- Customers get a reminder shortly before their pickup closes, unless they turn it off.
alter table radar_profile add column pickup_reminders boolean default true not null;
alter table orders add column reminder_sent_at timestamp(6) with time zone;
create index idx_orders_reminder on orders (status, pickup_deadline);
