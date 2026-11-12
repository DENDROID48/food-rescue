# Food Rescue

Connects local stores with people nearby to sell surplus food at a discount, and to give donated
food for free to verified people in need through Pay-It-Forward.

Spring Boot 3.3 backend (Java 21+) with a web page served from the same application.

## Run it locally

```bash
mvn spring-boot:run      # http://localhost:8080, in-memory database with demo data
mvn test                 # unit and integration tests
```

Or open the project in IntelliJ and run `FoodRescueApplication`.

Open http://localhost:8080/ and sign in with a demo account. Every one uses the password `demo1234`:

| Email | Role | What to try |
|---|---|---|
| `student@example.com` | consumer | Search an area, reserve food, cancel, ask to be verified |
| `beneficiary@example.com` | verified consumer | Claim a free Pay-It-Forward item |
| `owner@example.com` | store owner | Publish food with a photo, confirm a pickup code |
| `admin@example.com` | administrator | Approve the pending verification request, suspend a store |
| `applicant@example.com` | consumer | Has a verification request waiting for the admin |

By default the demo data is recreated on every start. The H2 database console is at http://localhost:8080/h2-console
(JDBC URL `jdbc:h2:mem:foodrescue`, user `sa`, no password).

## Ways to start

| Command | What happens |
|---|---|
| `mvn spring-boot:run` | Demo mode: demo data in memory, lost when the program stops |
| `mvn spring-boot:run -Dspring-boot.run.arguments="--mode=files"` | Data is kept in CSV files in `data/` and read back on the next start (demo accounts are added the first time) |
| `... -Dspring-boot.run.arguments="--mode=files --data-dir=other-folder --port=8081"` | Another data folder and port |
| `... -Dspring-boot.run.arguments="--spring.profiles.active=dev,files"` | The same as `--mode=files`, chosen through the `application-files.yml` config file |
| `... -Dspring-boot.run.arguments="--help"` | Lists the options |

With a built jar the same options follow `java -jar target/food-rescue-*.jar`. In IntelliJ put them in
**Run → Edit Configurations → Program arguments**. A wrong value (`--port=abc`, `--mode=cloud`) stops
the program with a one-line message.

In files mode the data is saved every 10 seconds and when the program stops (Ctrl+C), to
`users.csv`, `stores.csv`, `food-items.csv`, `photos.csv`, `orders.csv` and the `photos/` folder.
They open in a spreadsheet. If a file is edited by hand and a value is wrong, the program refuses
to start and names the file, line and column. Delete the folder to start over with the demo data.

The class diagram is in [docs/class-diagram.md](docs/class-diagram.md).

## How it works

- **Finding food:** consumers search by place name ("Iulius Town", "Strada Paris 5") or use their
  location, choose a distance, and see results in a list and on a map. Nobody types coordinates.
- **Reserving:** a reservation holds the units for 60 minutes (or until the food expires) and
  gives a 6-digit pickup code. Uncollected reservations are released automatically.
- **Pickup:** the store types the customer's code and hands the food over. Payment happens at the store.
- **Pay-It-Forward:** stores set aside free units. A consumer asks to be verified, an administrator
  approves them after checking outside the app, and verified people can claim one free item per day.
  Only the outcome is stored, never the reason.
- **Stores:** an owner registers a store by its address. The server finds it on the map, and the
  owner can drag the pin if it's slightly off.

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
| `FOODRESCUE_CONTACT_EMAIL` | recommended | Sent to OpenStreetMap's geocoder as a contact, as its usage policy asks |

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
| `GET /api/v1/stores/{storeId}/orders?status=RESERVED` | store owner | Reservations to prepare |
| `POST /api/v1/stores/{storeId}/pickups` | store owner | Hand over food: `{"pickupCode": "048213"}` |
| `GET /api/v1/admin/eligibility-requests` | admin | Verification requests waiting |
| `PUT /api/v1/admin/users/{userId}/eligibility` | admin | `{"approved": true}` or `false` |
| `GET /api/v1/admin/stores` | admin | All stores with owners |
| `PUT /api/v1/admin/stores/{storeId}/status` | admin | `{"active": false}` suspends a store |

## Code layout

| Package | Responsibility |
|---|---|
| `domain` | JPA entities and enums |
| `repository` | Spring Data interfaces |
| `service` | Business rules, address lookup, scheduled cleanup |
| `security` | Login tokens, access rules, `@CurrentUser` |
| `web` | REST controllers and error mapping |
| `dto` | Request and response objects |
| `config`, `util`, `exception` | Settings, demo data, geo maths, custom exceptions |
| `resources/static` | The web page |
| `resources/db/migration` | Database schema (Flyway) |

## Not built yet

- **Online payment:** customers pay at the store. Adding Stripe or a local provider needs an account with them.
- **Notifications:** for example email or push for "your pickup expires soon". This needs an email or push provider.
- **Password reset:** this also needs an email provider.
- **Several servers:** add ShedLock so only one server runs the reservation cleanup job.
- **Address lookup at scale:** OpenStreetMap's free geocoder allows about one request per second.
  For real traffic, use a paid provider or host your own Nominatim.
- **Very large catalogues:** move to PostGIS if there are hundreds of thousands of stores, and move
  photos to object storage (S3 or similar).
