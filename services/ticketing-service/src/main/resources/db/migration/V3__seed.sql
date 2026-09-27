-- Six events, spread across categories and UK cities, so a storefront looks real rather than seeded.
WITH e AS (
    INSERT INTO events (slug, title, subtitle, venue, city, category, starts_at, organiser, price_minor, currency)
    VALUES ('wildfire-manchester', 'Wildfire', 'Live at the Academy', 'O2 Academy', 'Manchester', 'Music',
            now() + interval '3 weeks', 'org-wildfire', 4500, 'GBP')
    RETURNING id
)
INSERT INTO seats (event_id, seat, row_label, seat_number)
SELECT e.id, chr(64 + r) || c, chr(64 + r), c FROM e, generate_series(1, 8) r, generate_series(1, 14) c;

WITH e AS (
    INSERT INTO events (slug, title, subtitle, venue, city, category, starts_at, organiser, price_minor, currency)
    VALUES ('laughtrack-hackney', 'Laugh Track Live', 'A night of new stand-up', 'Hackney Empire', 'London', 'Comedy',
            now() + interval '2 weeks', 'org-laughtrack', 2800, 'GBP')
    RETURNING id
)
INSERT INTO seats (event_id, seat, row_label, seat_number)
SELECT e.id, chr(64 + r) || c, chr(64 + r), c FROM e, generate_series(1, 6) r, generate_series(1, 12) c;

WITH e AS (
    INSERT INTO events (slug, title, subtitle, venue, city, category, starts_at, organiser, price_minor, currency)
    VALUES ('hamlet-bristol', 'Hamlet Reimagined', 'A modern-dress production', 'Bristol Old Vic', 'Bristol', 'Theatre',
            now() + interval '5 weeks', 'org-hamlet', 3500, 'GBP')
    RETURNING id
)
INSERT INTO seats (event_id, seat, row_label, seat_number)
SELECT e.id, chr(64 + r) || c, chr(64 + r), c FROM e, generate_series(1, 9) r, generate_series(1, 15) c;

WITH e AS (
    INSERT INTO events (slug, title, subtitle, venue, city, category, starts_at, organiser, price_minor, currency)
    VALUES ('sixnations-cardiff', 'Six Nations Warm-up', 'Wales vs a touring XV', 'Principality Stadium', 'Cardiff', 'Sport',
            now() + interval '6 weeks', 'org-sixnations', 8500, 'GBP')
    RETURNING id
)
INSERT INTO seats (event_id, seat, row_label, seat_number)
SELECT e.id, chr(64 + r) || c, chr(64 + r), c FROM e, generate_series(1, 10) r, generate_series(1, 16) c;

WITH e AS (
    INSERT INTO events (slug, title, subtitle, venue, city, category, starts_at, organiser, price_minor, currency)
    VALUES ('indienights-glasgow', 'Indie Nights', 'Four bands, one bill', 'Barrowland Ballroom', 'Glasgow', 'Music',
            now() + interval '4 weeks', 'org-indienights', 1800, 'GBP')
    RETURNING id
)
INSERT INTO seats (event_id, seat, row_label, seat_number)
SELECT e.id, chr(64 + r) || c, chr(64 + r), c FROM e, generate_series(1, 6) r, generate_series(1, 10) c;

WITH e AS (
    INSERT INTO events (slug, title, subtitle, venue, city, category, starts_at, organiser, price_minor, currency)
    VALUES ('tempest-edinburgh', 'The Tempest', 'Outdoor summer staging', 'Royal Lyceum', 'Edinburgh', 'Theatre',
            now() + interval '8 weeks', 'org-tempest', 3200, 'GBP')
    RETURNING id
)
INSERT INTO seats (event_id, seat, row_label, seat_number)
SELECT e.id, chr(64 + r) || c, chr(64 + r), c FROM e, generate_series(1, 7) r, generate_series(1, 13) c;

-- Two events with seats already SOLD (order_id NULL: no order ever backed them), so "few left" states exist.
-- The Six Nations warm-up is nearly sold out.
WITH target AS (
    SELECT s.event_id, s.seat,
           row_number() OVER (PARTITION BY s.event_id ORDER BY s.row_label, s.seat_number) AS rn,
           count(*) OVER (PARTITION BY s.event_id) AS total
      FROM seats s JOIN events e ON e.id = s.event_id
     WHERE e.slug = 'laughtrack-hackney'
)
UPDATE seats SET status = 'SOLD'
  FROM target
 WHERE seats.event_id = target.event_id AND seats.seat = target.seat AND target.rn <= ceil(target.total * 0.4);

WITH target AS (
    SELECT s.event_id, s.seat,
           row_number() OVER (PARTITION BY s.event_id ORDER BY s.row_label, s.seat_number) AS rn,
           count(*) OVER (PARTITION BY s.event_id) AS total
      FROM seats s JOIN events e ON e.id = s.event_id
     WHERE e.slug = 'sixnations-cardiff'
)
UPDATE seats SET status = 'SOLD'
  FROM target
 WHERE seats.event_id = target.event_id AND seats.seat = target.seat AND target.rn <= ceil(target.total * 0.9);
