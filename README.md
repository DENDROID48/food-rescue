# Food Rescue

Connects local stores with people nearby to sell surplus food at a discount, and to give donated
food for free to verified people in need through Pay-It-Forward.

Spring Boot 3.3 backend (Java 21+) with a web page served from the same application.

## Interface

The web client is in `src/main/resources/static/` and needs no build step:

- `index.html` — page structure for the sign-in, customer, store owner and admin screens, plus the dialogs.
- `styles.css` — the design system (colour, type, spacing, radius, shadow and motion tokens) and all styles.
- `app.js` — the client logic. It only uses the existing REST API.
- `photos/` — freely licensed illustrative food photos (WebP, 480 and 960 px), credited in
  `photos/CREDITS.md` and under "Photo credits" in the app.

A listing shows the store's own photo when it has one. Otherwise the app picks an illustrative photo
from words in the item's name (for example "croissant", "salad", "vegetable box") and labels it
"Illustrative photo"; if nothing matches it shows a neutral placeholder. Maps use Leaflet and
OpenStreetMap; without them, food search still works as a list.

## Run it locally

```bash
mvn spring-boot:run      # http://localhost:8080, in-memory database with demo data
mvn test                 # 157 unit and integration tests
```

Browser tests that drive the real page are in [`e2e/`](e2e/README.md).

Settings come from `src/main/resources/application.yml`. Any of them can be changed for one run
with a program argument, for example `--server.port=9090` or
`--foodrescue.max-quantity-per-reservation=2`.

Or open the project in IntelliJ and run `FoodRescueApplication`.

Open http://localhost:8080/ and sign in with a demo account. Every one uses the password `demo1234`:

| Email | Role | What to try |
|---|---|---|
| `student@example.com` | consumer | Search an area, reserve food, cancel, ask to be verified |
| `beneficiary@example.com` | verified consumer | Claim a free Pay-It-Forward item |
| `owner@example.com` | store owner | Publish food with a photo, confirm a pickup code |
| `admin@example.com` | administrator | Approve the pending verification request, suspend a store |
| `applicant@example.com` | consumer | Has a verification request waiting for the admin |

The demo data is recreated on every start. Uploaded photos are saved as files in the `uploads/`
folder next to where the app was started (`uploads/templates/7` is the photo of product 7,
`uploads/food-items/42` of offer 42); they survive restarts, but the demo data that pointed to
them does not. The H2 database console is at http://localhost:8080/h2-console
(JDBC URL `jdbc:h2:mem:foodrescue`, user `sa`, no password).

## How it works

- **Finding food:** consumers search by place name ("Iulius Town", "Strada Paris 5") or use their
  location, choose a distance, and see results in a list and on a map. Nobody types coordinates.
- **Reserving:** a reservation gives a 6-digit pickup code. It holds the units until the end of the
  offer's pickup window, or for 60 minutes (at most until the food expires) when the offer has no
  window. Uncollected reservations are released automatically.
- **Pickup:** the store types the customer's code and hands the food over. Payment happens at the store.
- **Pay-It-Forward:** stores set aside free units. A consumer asks to be verified, an administrator
  approves them after checking outside the app, and verified people can claim one free item per day.
  Only the outcome is stored, never the reason.
- **Stores:** an owner registers a store by its address. The server finds it on the map, and the
  owner can drag the pin if it's slightly off.
- **Daily inventory:** see below.

## Daily inventory

Store owners publish the day's surplus in about a minute:

1. **Products, saved once.** A product (template) holds the name, description, photo, prices, kind
   (single product or Surprise Bag) and usual pickup window. It has **no quantity**.
2. **Set up today's offers.** The dashboard lists the saved products. The owner enters today's
   units for sale and free (Pay-It-Forward) units, checks the pickup times, confirms the quantities
   are really available, and publishes everything in one request (or saves drafts).
3. **During the day.** Offers can be edited (units still on the shelf, pickup window), paused,
   resumed or removed. Reservations made earlier stay valid when an offer is paused or removed.

Rules:

- **Nothing is carried over.** Quantities are always typed in for the day. An offer ends with its
  pickup window; from then on it is no longer listed, reservable or part of "today".
- **Time zones.** Every store has a time zone (`Europe/Bucharest` by default; new stores take the
  owner's device zone). "Today", pickup times like 18:00 and the daily figures use that zone,
  whatever zone the server or a phone is in.
- **States.** Draft, Live, Paused (decided by the store), Sold out and Expired (worked out from the
  stock and the clock, so they are never out of date).
- **Concurrency.** Reservations, cancellations, pickups, edits and the cleanup job lock the offer's
  row, so units are never sold twice (`ConcurrentReservationTest` runs 8 simultaneous reservations
  for 3 units). Publishing locks the product, so the same product cannot be published twice a day.
- **Figures.** Available, reserved, collected today and donated today are computed from the
  offers and orders in the database. Collected units are counted from the pickup time, which is
  recorded since this feature was added.
- **Reminder.** Optional, per store: after the chosen local time, the dashboard shows a reminder
  when nothing was published or drafted today. It is calculated when the dashboard loads, so it
  needs no background job or messaging service. The same rule can later drive email or push.

## Store Autopilot and Rescue Drops

- **Autopilot suggestions.** In "Set up today's offers", the Autopilot shows each product's suggested
  quantity, pickup window and, if a lot usually goes unsold, a lower price. Suggestions come from that
  product's offers over the last 28 days, and days on the same weekday count double. They are simple,
  explained statistics, such as "Sold out on 3 of the last 4 days", with no external AI service.
  "Fill in suggestions" only fills the form. A price changes only when the owner taps the suggestion,
  and only for today. Publishing still needs the confirmation tick.
- **Alerts on the dashboard.** Computed when the dashboard loads:
  - an offer is selling fast (low stock);
  - an offer sold out well before its pickup window ended;
  - drafts are not yet published;
  - leftovers end within 2 hours.
- **Rescue Drops.** A short offer (10 to 240 minutes) that starts the moment it is launched. It can
  be made from a saved product, or with **Smart Bundle**, which moves leftover units of today's offers
  into surprise bags. The source offers are locked and reduced in the same transaction. A drop is an
  ordinary offer, so reservations, pickup codes and Pay-It-Forward work unchanged. It ends by itself
  when it sells out or its time is up.
- **Drop alerts.** Customers opt in from the bell icon, choosing a place, a radius of up to 10 km and
  a daily maximum. Alerts are saved in the same transaction as the drop. Each customer gets at most
  one alert per shop every 2 hours and never more than their own daily maximum. Alerts go to an
  in-app inbox, checked every minute while the app is open. With the browser's permission, they also
  show as system notifications while the tab is in the background. No paid messaging service is used.

## Rescue Radar

- **Favourites.** Customers tap ☆ on a product or "Follow" on a shop. A favourite product follows the
  shop's saved product, so it carries over to every day's offer. A "Favourites" filter shows only those.
- **Food types.** Shops tag products, for example bakery, fruit & veg, vegetarian or vegan. The tags
  are shown as the shop's own statement, and the app never adds allergen or food-safety claims. A
  Smart Bundle is vegetarian or vegan only if every part of it is.
- **"For you".** A row of up to 6 picks, each with its reason, such as "One of your favourites",
  "You've had this 3 times before" or "Matches your interest in bakery". Scoring uses favourites,
  pickups in the last 120 days and interests, then distance, discount, how soon it ends, live drops
  and free units for verified customers. An offer is picked only for a personal reason. It can be
  turned off. Shops never see any of it.
- **Alerts.** These come on top of nearby Rescue Drop alerts, and all are opt-in:
  - favourite products on offer; several from one shop are merged into one alert;
  - drops from shops the customer follows, wherever the customer is;
  - a daily summary at a chosen time.

  The anti-spam rules apply to every alert:
  - at most one alert per shop every 2 hours;
  - a daily maximum, halved while 5 or more alerts from the last week are unopened;
  - quiet hours, which can run overnight; during them alerts wait silently in the inbox.
- **Web Push.** Alerts reach the device even when the app is closed. Messages go straight to the
  browsers' push services (Google, Mozilla, Apple, Microsoft) with VAPID keys and end-to-end
  encryption (RFC 8291). This is free and needs no account.
  - **Security:** the server only accepts https addresses of those services, so it can't be tricked
    into calling other addresses.
  - **Cleanup:** dead subscriptions are removed automatically.
  - **Shared devices:** signing out removes the device's subscription.
  - **Installing:** the site has a web-app manifest, so it can be installed. On iPhone and iPad, push
    only works after "Add to Home Screen".

## Smart Analytics

- **Insights tab** (store dashboard, last 7 days, 4 weeks or 3 months). Shows:
  - food rescued, in units and kg;
  - collected of offered, left over and not collected;
  - takings and customers' savings;
  - a daily chart with a table view;
  - a per-product table;
  - when customers come;
  - Rescue Drop results;
  - audience counts, hidden below 5 for privacy.

  Figures count only offers that have ended, by day in the store's time zone. The quantity each
  offer was published with is recorded and adjusted when the owner corrects the shelf. Older offers
  rebuild it from their orders.
- **Forecast:** expected demand per product for the next 7 days, with a range. It is weighted to the
  same weekday, uses 8 weeks of history, and shows its own track record ("right on 11 of the last 11
  days"). With too little history it says so.
- **Recommendations:** explained rules, for example:
  - a product sells out;
  - much is left over;
  - pickups bunch at the end of the window;
  - many no-shows;
  - missing weights;
  - days without offers.

  Each comes with its evidence and a button that opens the right screen. Nothing changes
  automatically.
- **Pickup reminders:** customers get a reminder 30 minutes before their pickup closes. It is on by
  default, can be turned off in Alerts → Settings, and stays silent during quiet hours. The pickup
  code is never put in the message.
- **CSV export:** one line per ended offer. Cells that could run as spreadsheet formulas are
  neutralised.
- **Admin overview:** platform totals for 28 days, stores quiet for 7 days, and stores with high waste
  or no-show rates.
- **Weights:** products and one-off offers can have an optional approximate weight per unit.
- **JDBC:** the aggregate figures are read with plain SQL through `NamedParameterJdbcTemplate`
  (`AnalyticsService`). Each pickup reminder is claimed with one SQL `UPDATE … where
  reminder_sent_at is null`, so it can never be sent twice, even with several servers.

## AI-assisted listings

- **Suggest details from a photo:** in the product editor and the one-off offer editor, after
  choosing a photo, the owner can ask for a suggested name, description and food types.
- **Free and private:** a free image model (MobileNet, via TensorFlow.js from cdn.jsdelivr.net)
  runs in the owner's browser. No paid service is used and the photo is not sent anywhere for this.
  The model is downloaded the first time it is used (about 14 MB), then comes from the browser cache.
- **The server decides what the labels mean** (`ListingSuggestionService`). It maps the model's
  ImageNet labels (for example "French loaf") to a food type and a plain description, and ignores
  guesses under 10%.
- **Human approval:** nothing is filled in until the owner presses "Use these", and the panel shows
  what the model saw and how sure it was. The model can be wrong, so the owner always checks.
- **No invented food-safety facts:** dietary labels (vegetarian, vegan) are never suggested, because
  a photo cannot show what food is free of. The description never mentions allergens, ingredients or
  freshness, and tells customers to ask the shop.

## Gamification

- **My impact** (customers): food rescued in items and kg, money saved compared with full prices,
  number of rescues and shops supported. Only collected orders count.
- **Rescue streak:** weeks in a row (Monday to Sunday) with at least one collected order. Weeks rather
  than days, so the app never pushes people to buy food they don't need. A streak stays alive until
  a whole week is missed.
- **Achievements:** nine milestones (first rescue, 10 and 50 rescues, 5 and 25 kg, 100 lei saved,
  3 different shops, a Rescue Drop, 4 weeks in a row) with progress bars. Each unlocked one shows the
  day it was reached. New ones are marked "New" and announced once.
- **Community challenge:** a monthly target for everyone together, set in `application.yml`
  (`foodrescue.gamification.monthly-challenge-units`, default 400), with the customer's own share.
- **Nothing extra is stored:** `GamificationService` works everything out from the order history,
  so the figures always match it. The `Achievement` enum holds each milestone's figure and target.

## Production

Production uses PostgreSQL. Run it with Docker:

```bash
export FOODRESCUE_JWT_SECRET=$(openssl rand -hex 32)
export FOODRESCUE_ADMIN_EMAIL=you@example.com
export FOODRESCUE_ADMIN_PASSWORD='a long admin password'
docker compose up --build        # http://localhost:8080 (APP_PORT=8081 to use another port)
```

Or run the jar yourself:

```bash
mvn package
SPRING_PROFILES_ACTIVE=postgres DB_URL=jdbc:postgresql://host:5432/foodrescue DB_USERNAME=... DB_PASSWORD=... \
FOODRESCUE_JWT_SECRET=... java -jar target/food-rescue-0.1.0-SNAPSHOT.jar
```

| Variable | Required | Purpose |
|---|---|---|
| `FOODRESCUE_JWT_SECRET` | yes | Signs login tokens; at least 32 random characters. Changing it signs everyone out. |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | yes | PostgreSQL connection |
| `FOODRESCUE_ADMIN_EMAIL`, `FOODRESCUE_ADMIN_PASSWORD` | first start | Creates the first administrator (password at least 12 characters). Nothing happens if the account exists. |
| `FOODRESCUE_CONTACT_EMAIL` | recommended | Contact for OpenStreetMap's geocoder and for push services (VAPID subject) |
| `FOODRESCUE_UPLOADS_DIR` | no | Folder for uploaded photos (default `uploads`, in Docker `/app/uploads` on its own volume). Back it up together with the database. |
| `FOODRESCUE_PUSH_PUBLIC_KEY`, `FOODRESCUE_PUSH_PRIVATE_KEY` | for push | VAPID key pair, for example from `npx web-push generate-vapid-keys`. Keep them fixed: browsers must subscribe again when they change. Without them, temporary keys are used. Push also needs HTTPS. |

The production profile starts with an empty database and no demo accounts.

**Database changes** are Flyway migrations in `src/main/resources/db/migration`. They run
automatically at startup, on H2 and PostgreSQL alike. Never edit a migration that already ran;
add `V2__description.sql` and so on.

## API

Sign in with `POST /api/v1/auth/login`, then send the returned token as `Authorization: Bearer <token>`.
Errors are JSON in the RFC 7807 format, for example `{"status": 409, "detail": "Only 1 unit(s) left"}`.

| Method and path | Who | What |
|---|---|---|
| `POST /api/v1/users` | anyone | Create a CONSUMER or STORE_OWNER account; returns a login token |
| `POST /api/v1/auth/login` | anyone | Sign in; returns a login token |
| `GET /api/v1/users/me` | signed in | Own account |
| `POST /api/v1/users/me/eligibility-request` | consumer | Ask to be verified for free items |
| `GET /api/v1/geocode?q=` | anyone | Places matching a name or address |
| `GET /api/v1/geocode/reverse?lat=&lng=` | anyone | Address nearest to a point |
| `GET /api/v1/food-items/nearby?lat=&lng=&radiusKm=` | anyone | Available food, nearest first |
| `GET /api/v1/food-items/{id}/photo` | anyone | Photo of an item |
| `POST /api/v1/food-items/{id}/reservations` | consumer | Reserve at the discounted price: `{"quantity": 2}` |
| `POST /api/v1/food-items/{id}/free-claims` | verified consumer | Claim one free unit |
| `GET /api/v1/orders/mine` | consumer | Order history with pickup codes |
| `POST /api/v1/orders/{id}/cancel` | consumer | Cancel an open reservation; the stock is returned |
| `POST /api/v1/stores` | store owner | Register a store: `{"name": "...", "address": "..."}` |
| `PUT /api/v1/stores/{storeId}` | store owner | Edit name and address |
| `GET /api/v1/stores/mine` | store owner | Own stores |
| `GET/POST /api/v1/stores/{storeId}/food-items` | store owner | List or publish food |
| `PUT/DELETE /api/v1/stores/{storeId}/food-items/{itemId}` | store owner | Edit, or take off the market |
| `POST/DELETE /api/v1/stores/{storeId}/food-items/{itemId}/photo` | store owner | Upload (multipart field `file`, JPEG/PNG/WebP, max 2 MB) or remove a photo |
| `GET/POST /api/v1/stores/{storeId}/templates` | store owner | List or create saved products (pickup times like `"18:00"`) |
| `PUT/DELETE /api/v1/stores/{storeId}/templates/{templateId}` | store owner | Edit, or archive a product |
| `POST/DELETE /api/v1/stores/{storeId}/templates/{templateId}/photo` | store owner | Product photo (copied to each day's offer) |
| `GET /api/v1/template-photos/{templateId}` | anyone | Photo of a product |
| `GET /api/v1/stores/{storeId}/today` | store owner | Today's offers, products, figures and reminder, in the store's time zone |
| `POST /api/v1/stores/{storeId}/offers` | store owner | Publish (or draft) today's offers from products, all or nothing |
| `PATCH /api/v1/stores/{storeId}/offers/{itemId}` | store owner | Change today's units and pickup window |
| `PUT /api/v1/stores/{storeId}/offers/{itemId}/status` | store owner | `{"status": "ACTIVE"}` publishes or resumes, `"PAUSED"` hides |
| `PUT /api/v1/stores/{storeId}/reminder` | store owner | `{"enabled": true, "time": "15:00"}` |
| `POST /api/v1/stores/{storeId}/reminder/dismiss` | store owner | Hide today's reminder |
| `GET /api/v1/stores/{storeId}/autopilot` | store owner | Suggested quantities, windows and prices for today (publishes nothing) |
| `GET /api/v1/stores/{storeId}/drops/audience` | store owner | How many opted-in customers a drop would reach |
| `POST /api/v1/stores/{storeId}/drops` | store owner | Launch a Rescue Drop of a saved product, starting now |
| `POST /api/v1/stores/{storeId}/drops/bundle` | store owner | Bundle leftovers of today's offers into surprise bags, as a drop |
| `GET /api/v1/me/notifications` | consumer | Newest alerts and the unread count |
| `POST /api/v1/me/notifications/read` | consumer | Mark all alerts as read |
| `GET/PUT /api/v1/me/drop-alerts` | consumer | Drop alert settings: place, radius, daily maximum |
| `GET /api/v1/me/radar?lat=&lng=&radiusKm=` | consumer | "For you" picks with reasons, plus favourite ids |
| `GET /api/v1/me/favourites` | consumer | Favourite products and followed shops |
| `PUT/DELETE /api/v1/me/favourites/stores/{storeId}` | consumer | Follow or unfollow a shop |
| `PUT/DELETE /api/v1/me/favourites/products/{templateId}` | consumer | Add or remove a favourite product |
| `GET/PUT /api/v1/me/radar-profile` | consumer | Interests, favourite alerts, quiet hours, daily summary, personalisation |
| `GET /api/v1/stores/{storeId}/insights?days=28` | store owner | Results, forecast and recommendations (7–90 days) |
| `GET /api/v1/stores/{storeId}/insights/export.csv?days=28` | store owner | One line per ended offer |
| `GET /api/v1/admin/insights` | admin | Platform totals, quiet stores, stores needing attention |
| `GET /api/v1/push/public-key` | anyone | VAPID key for subscribing a browser |
| `GET /api/v1/settings` | anyone | rules the page shows, read from configuration (max units per reservation) |
| `GET /api/v1/me/impact` | consumer | Personal impact, weekly streak, achievements and the monthly community challenge |
| `POST /api/v1/stores/listing-suggestions` | store owner | Body: the image model's labels and the typed name. Returns a suggestion; saves nothing |
| `POST/DELETE /api/v1/me/push-subscriptions` | consumer | Save or remove this browser's push subscription |
| `POST /api/v1/me/push-subscriptions/test` | consumer | Send a test push to this customer's browsers |
| `GET /api/v1/stores/{storeId}/orders?status=RESERVED` | store owner | Reservations to prepare |
| `POST /api/v1/stores/{storeId}/pickups` | store owner | Hand over food: `{"pickupCode": "048213"}` |
| `GET /api/v1/admin/eligibility-requests` | admin | Verification requests waiting |
| `PUT /api/v1/admin/users/{userId}/eligibility` | admin | `{"approved": true}` or `false` |
| `GET /api/v1/admin/stores` | admin | All stores with owners |
| `PUT /api/v1/admin/stores/{storeId}/status` | admin | `{"active": false}` suspends a store |

## Code layout

| Package | Responsibility |
|---|---|
| `domain` | JPA entities, enums, and the interfaces `Expirable` (offers and orders that run out) and `Locatable` (stores, alert areas and search points on the map) |
| `repository` | Spring Data interfaces |
| `service` | Business rules, address lookup, alerts, analytics, scheduled jobs |
| `storage` | Photo files: the `PhotoStorage` interface and `FilePhotoStorage`, which keeps them in the uploads folder |
| `push` | Web Push: subscriptions, encryption and sending in the background |
| `security` | Login tokens, access rules, `@CurrentUser` |
| `web` | REST controllers and error mapping |
| `dto` | Request and response objects |
| `config`, `util`, `exception` | Settings, demo data, geo maths, custom exceptions |
| `resources/static` | The web page |
| `resources/db/migration` | Database schema (Flyway) |
| `docs/class-diagram.md` | Class diagram (Mermaid): open it in IntelliJ's Markdown preview or paste it into https://mermaid.live |

## Not built yet

- **Online payment:** customers pay at the store. Adding Stripe or a local provider needs an account with them.
- **Email or SMS alerts:** they need a provider. Web Push covers alerts outside the app for free.
- **Password reset:** this also needs an email provider.
- **Several servers:** add ShedLock so only one server runs the reservation cleanup job.
- **Address lookup at scale:** OpenStreetMap's free geocoder allows about one request per second.
  For real traffic, use a paid provider or host your own Nominatim.
- **Very large catalogues:** move to PostGIS if there are hundreds of thousands of stores, and move
  photos to object storage (S3 or similar) by adding a second `PhotoStorage` implementation.
