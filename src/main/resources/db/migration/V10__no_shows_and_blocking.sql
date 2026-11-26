-- Administrators can block an account; the note is for other administrators only.
alter table app_user add column blocked_at timestamp(6) with time zone;
alter table app_user add column blocked_reason varchar(300);
-- Missed pickups are counted from expired reservations by their pickup deadline.
create index idx_orders_user_status_deadline on orders (user_id, status, pickup_deadline);
