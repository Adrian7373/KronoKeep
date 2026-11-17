-- KronoKeep demo data: one believable household for screenshots, the demo video and reviewers.
--
-- Usage: create the demo user first (sign up in the app, or Dashboard → Authentication → Add user), put
-- its email in `demo_email` below, then run this whole file as `postgres` (SQL editor). Not a migration and
-- not `supabase/seed.sql` (the CLI would run that on every local reset, where this user doesn't exist).
--
-- * Dates are relative to "today 10:00 Manila", so a fresh seed always shows the same picture:
--   scooter engine oil OVERDUE, scooter gear oil and aircon cleaning DUE SOON, everything else OK,
--   aircon warranty ending in ~5 weeks, TV warranty expired ~3 weeks ago.
-- * Fixed UUIDs + `on conflict do nothing`: running it again changes nothing (no hard deletes, ever).
-- * Due fields are left for the engine: the app computes them live and recompute-due fills them hourly.
-- * No documents or reports: those need storage objects; make them in the app (attach a receipt,
--   generate a verified report) so the real upload and PDF paths are what's shown.

do $$
declare
  demo_email constant text := 'REPLACE_WITH_DEMO_EMAIL';
  u    uuid;
  base timestamptz := (date_trunc('day', now() at time zone 'Asia/Manila') + interval '10 hours') at time zone 'Asia/Manila';
  today date := (now() at time zone 'Asia/Manila')::date;

  -- assets
  a_click  constant uuid := 'de300000-0000-4000-8000-0000000000a1';
  a_vios   constant uuid := 'de300000-0000-4000-8000-0000000000a2';
  a_aircon constant uuid := 'de300000-0000-4000-8000-0000000000a3';
  a_water  constant uuid := 'de300000-0000-4000-8000-0000000000a4';
  a_laptop constant uuid := 'de300000-0000-4000-8000-0000000000a5';
  a_tv     constant uuid := 'de300000-0000-4000-8000-0000000000a6';

  -- schedules
  s_oil    constant uuid := 'de300000-0000-4000-8000-0000000000b1';
  s_gear   constant uuid := 'de300000-0000-4000-8000-0000000000b2';
  s_cvt    constant uuid := 'de300000-0000-4000-8000-0000000000b3';
  s_air    constant uuid := 'de300000-0000-4000-8000-0000000000b4';
  s_pms    constant uuid := 'de300000-0000-4000-8000-0000000000b5';
  s_clean  constant uuid := 'de300000-0000-4000-8000-0000000000b6';
  s_filter constant uuid := 'de300000-0000-4000-8000-0000000000b7';
begin
  select id into u from auth.users where lower(email) = lower(demo_email);
  if u is null then
    raise exception 'No auth user with email %; create the demo user first', demo_email;
  end if;

  -- -------------------------------------------------------------------------
  -- Assets
  -- -------------------------------------------------------------------------
  insert into public.assets (id, user_id, category, name, brand, model, serial_no, purchase_date, price_centavos,
                             warranty_expires_on, current_odometer_km, weekly_km_baseline, notes, created_at)
  values
    (a_click, u, 'vehicle', 'Daily scooter', 'Honda', 'Click 125i', 'KF41E-2304517',
     today - 420, 7990000, today - 420 + 1095, 9800, 150,
     'Plate NCR 123 AB. Commute Marikina to Ortigas.', base - interval '420 days'),
    (a_vios, u, 'vehicle', 'Family car', 'Toyota', 'Vios 1.3 XLE CVT', 'MR2B29F30N1234567',
     today - 760, 79900000, today - 760 + 1095, 41380, 260,
     'Weekend trips and school run.', base - interval '400 days'),
    (a_aircon, u, 'appliance', 'Bedroom aircon', 'Carrier', 'Optima 1.5 HP inverter split', 'CRR-15I-0042817',
     today - 330, 3850000, today - 330 + 365, null, null,
     'Installed by the store; 1-year parts and labour warranty.', base - interval '330 days'),
    (a_water, u, 'appliance', 'Kitchen water purifier', 'Pureflow', 'PF-300', 'PF300-88213',
     today - 520, 1299000, today - 520 + 730, null, null,
     null, base - interval '400 days'),
    (a_laptop, u, 'electronic', 'Work laptop', 'Lenovo', 'ThinkPad E14 Gen 6', 'PF-4X9K2M',
     today - 240, 5899000, today - 240 + 365, null, null,
     'Bought at an authorised reseller, SM Megamall.', base - interval '240 days'),
    (a_tv, u, 'electronic', 'Living room TV', 'Samsung', '55" Crystal UHD', 'UA55CU8000-0J7Q',
     today - 755, 3299000, today - 755 + 730, null, null,
     null, base - interval '400 days')
  on conflict (id) do nothing;

  -- -------------------------------------------------------------------------
  -- Odometer readings (scooter ~21 km/day, car ~38 km/day, low variance → high confidence)
  -- -------------------------------------------------------------------------
  insert into public.odometer_readings (id, user_id, asset_id, km, recorded_at)
  values
    ('de300000-0000-4000-8000-0000000000d1', u, a_click, 4800, base - interval '240 days'),
    ('de300000-0000-4000-8000-0000000000d2', u, a_click, 5420, base - interval '210 days'),
    ('de300000-0000-4000-8000-0000000000d3', u, a_click, 6050, base - interval '180 days'),
    ('de300000-0000-4000-8000-0000000000d4', u, a_click, 6680, base - interval '150 days'),
    ('de300000-0000-4000-8000-0000000000d5', u, a_click, 7330, base - interval '120 days'),
    ('de300000-0000-4000-8000-0000000000d6', u, a_click, 7950, base - interval '90 days'),
    ('de300000-0000-4000-8000-0000000000d7', u, a_click, 8500, base - interval '64 days'),
    ('de300000-0000-4000-8000-0000000000d8', u, a_click, 9230, base - interval '30 days'),
    ('de300000-0000-4000-8000-0000000000d9', u, a_click, 9800, base - interval '3 days'),
    ('de300000-0000-4000-8000-0000000000da', u, a_vios, 33900, base - interval '200 days'),
    ('de300000-0000-4000-8000-0000000000db', u, a_vios, 36150, base - interval '140 days'),
    ('de300000-0000-4000-8000-0000000000dc', u, a_vios, 39000, base - interval '65 days'),
    ('de300000-0000-4000-8000-0000000000dd', u, a_vios, 40700, base - interval '20 days'),
    ('de300000-0000-4000-8000-0000000000de', u, a_vios, 41380, base - interval '2 days')
  on conflict (id) do nothing;

  -- -------------------------------------------------------------------------
  -- Schedules (preset names and intervals from domain/presets/Presets.kt). last_done_* = latest log.
  -- -------------------------------------------------------------------------
  insert into public.maintenance_schedules (id, user_id, asset_id, task, interval_days, interval_km,
                                            last_done_at, last_done_km, created_at)
  values
    -- Due 60 days after 64 days ago → overdue by 4 days.
    (s_oil, u, a_click, 'Engine oil change', 60, 1500, base - interval '64 days', 8500, base - interval '400 days'),
    -- Due at 10,100 km; ~300 km left at ~21 km/day → due soon.
    (s_gear, u, a_click, 'Gear oil change', null, 5000, base - interval '225 days', 5100, base - interval '400 days'),
    (s_cvt, u, a_click, 'CVT cleaning', null, 5000, base - interval '90 days', 7950, base - interval '400 days'),
    (s_air, u, a_click, 'Air filter replacement', null, 10000, base - interval '240 days', 4800, base - interval '400 days'),
    (s_pms, u, a_vios, 'PMS (preventive maintenance)', 180, 5000, base - interval '65 days', 39000, base - interval '400 days'),
    -- 90-day interval, done 80 days ago → due in 10 days.
    (s_clean, u, a_aircon, 'Aircon cleaning', 90, null, base - interval '80 days', null, base - interval '330 days'),
    (s_filter, u, a_water, 'Filter cartridge replacement', 180, null, base - interval '150 days', null, base - interval '400 days')
  on conflict (id) do nothing;

  -- -------------------------------------------------------------------------
  -- Service history. A multi-task visit is one row per task with the cost on the first (as the app does).
  -- -------------------------------------------------------------------------
  insert into public.service_logs (id, user_id, asset_id, schedule_id, performed_at, odometer_km, cost_centavos, shop, notes)
  values
    -- Scooter
    ('de300000-0000-4000-8000-0000000000c1', u, a_click, s_oil, base - interval '330 days', 2600, 35000,
     'Rider''s Hub Moto Care, Marikina', 'First change after break-in.'),
    ('de300000-0000-4000-8000-0000000000c2', u, a_click, s_oil, base - interval '240 days', 4800, 67000,
     'Rider''s Hub Moto Care, Marikina', 'Oil and air filter in one visit.'),
    ('de300000-0000-4000-8000-0000000000c3', u, a_click, s_air, base - interval '240 days', 4800, null,
     'Rider''s Hub Moto Care, Marikina', null),
    ('de300000-0000-4000-8000-0000000000c4', u, a_click, s_gear, base - interval '225 days', 5100, 63000,
     'Rider''s Hub Moto Care, Marikina', 'Gear oil plus CVT cleaning.'),
    ('de300000-0000-4000-8000-0000000000c5', u, a_click, s_cvt, base - interval '225 days', 5100, null,
     'Rider''s Hub Moto Care, Marikina', null),
    ('de300000-0000-4000-8000-0000000000c6', u, a_click, s_oil, base - interval '150 days', 6680, 35000,
     'Rider''s Hub Moto Care, Marikina', null),
    ('de300000-0000-4000-8000-0000000000c7', u, a_click, null, base - interval '120 days', 7330, 185000,
     'Kalye Tire Supply, Cainta', 'Rear tire worn to the wear bars; replaced with 100/90-14.'),
    ('de300000-0000-4000-8000-0000000000c8', u, a_click, s_cvt, base - interval '90 days', 7950, 45000,
     'Rider''s Hub Moto Care, Marikina', 'Belt still good; rollers and bell cleaned.'),
    ('de300000-0000-4000-8000-0000000000c9', u, a_click, s_oil, base - interval '64 days', 8500, 38000,
     'Rider''s Hub Moto Care, Marikina', null),
    -- Car
    ('de300000-0000-4000-8000-0000000000ca', u, a_vios, s_pms, base - interval '380 days', 29000, 450000,
     'Northside Auto Care, Quezon City', '30,000 km PMS.'),
    ('de300000-0000-4000-8000-0000000000cb', u, a_vios, s_pms, base - interval '200 days', 33900, 395000,
     'Northside Auto Care, Quezon City', 'Oil, oil filter, brake cleaning and adjustment.'),
    ('de300000-0000-4000-8000-0000000000cc', u, a_vios, null, base - interval '100 days', 37620, 580000,
     'Northside Auto Care, Quezon City', 'Battery replaced (2SM maintenance-free). Old one failed a load test.'),
    ('de300000-0000-4000-8000-0000000000cd', u, a_vios, s_pms, base - interval '65 days', 39000, 520000,
     'Northside Auto Care, Quezon City', '40,000 km PMS: oil, oil and air filters, brake fluid.'),
    -- Aircon
    ('de300000-0000-4000-8000-0000000000ce', u, a_aircon, s_clean, base - interval '170 days', null, 120000,
     'CoolBreeze Aircon Services', 'General cleaning, indoor and outdoor units.'),
    ('de300000-0000-4000-8000-0000000000cf', u, a_aircon, s_clean, base - interval '80 days', null, 120000,
     'CoolBreeze Aircon Services', 'Filters were very dusty; drain line flushed.'),
    -- Water purifier
    ('de300000-0000-4000-8000-0000000000e1', u, a_water, s_filter, base - interval '330 days', null, 165000,
     null, 'Replaced cartridges myself.'),
    ('de300000-0000-4000-8000-0000000000e2', u, a_water, s_filter, base - interval '150 days', null, 165000,
     null, 'Replaced cartridges myself.')
  on conflict (id) do nothing;
end
$$;
