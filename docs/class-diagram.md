# Food Rescue: class diagram

Classes, interfaces and the relationships between them. Spring's generated repository
interfaces and the JSON request/response records are left out to keep the picture readable.
(Opens as a picture in IntelliJ's Markdown preview, on GitHub, or at https://mermaid.live.)

```mermaid
classDiagram
    direction TB

    %% ---------- Interfaces ----------
    class Locatable {
        <<interface>>
        +getLatitude() double
        +getLongitude() double
        +distanceKmTo(Locatable other) double
    }
    class Expirable {
        <<interface>>
        +expiryMoment() Instant
        +isExpiredAt(Instant now) boolean
    }

    %% ---------- Domain ----------
    class User {
        -Long id
        -String fullName
        -String email
        -String passwordHash
        -UserRole role
        -ConsumerCategory category
        -EligibilityStatus eligibilityStatus
        -Instant createdAt
    }
    class Store {
        -Long id
        -String name
        -String address
        -double latitude
        -double longitude
        -boolean active
        -User owner
    }
    class GeoPoint {
        <<record>>
        +double latitude
        +double longitude
    }
    class FoodItem {
        -Long id
        -Store store
        -String name
        -BigDecimal originalPrice
        -BigDecimal discountedPrice
        -int quantityAvailable
        -int payItForwardQuantity
        -Instant expiresAt
        -boolean hasPhoto
        +returnStock(OrderType type, int quantity)
        +isNotExpired(Instant now) boolean
        +discountPercent() int
    }
    class FoodItemPhoto {
        -Long foodItemId
        -String contentType
        -byte[] data
    }
    class Order {
        -Long id
        -User user
        -FoodItem foodItem
        -int quantity
        -BigDecimal totalPrice
        -OrderType type
        -OrderStatus status
        -String pickupCode
        -Instant pickupDeadline
    }
    class UserRole {
        <<enumeration>>
        CONSUMER
        STORE_OWNER
        ADMIN
    }
    class EligibilityStatus {
        <<enumeration>>
        NOT_REQUESTED
        PENDING
        APPROVED
        REJECTED
    }
    class OrderType {
        <<enumeration>>
        PURCHASE
        PAY_IT_FORWARD
    }
    class OrderStatus {
        <<enumeration>>
        RESERVED
        PICKED_UP
        CANCELLED
        EXPIRED
    }

    %% ---------- Services ----------
    class FoodItemService {
        +findAvailableNearby(lat, lng, radiusKm) List~NearbyFoodItemResponse~
        +reserve(userId, itemId, quantity) OrderResponse
        +claimPayItForward(userId, itemId) OrderResponse
    }
    class OrderService {
        +listOwnOrders(userId) List~OrderResponse~
        +cancel(userId, orderId) OrderResponse
        +confirmPickup(ownerId, storeId, code) OrderResponse
    }
    class ReservationExpiryJob {
        +releaseExpiredReservations()
    }
    class GeocodingService {
        -Map~String, List~ cache
        +search(String query) List~PlaceResponse~
        +locateAddress(String address) PlaceResponse
    }

    %% ---------- Files and start-up ----------
    class FileDataStore {
        -Map~String, String~ writtenFiles
        -Map~Long, Integer~ writtenPhotos
        +load()
        +save()
    }
    class CsvFormat {
        +line(List values)$ String
        +parse(String text, String fileName)$ List~Row~
    }
    class CsvRecord {
        -Map~String, Integer~ columns
        +text(column, maxLength) String
        +money(column) BigDecimal
        +instant(column) Instant
        +enumValue(column, type) Enum
    }
    class StorageProperties {
        <<record>>
        +StorageMode mode
        +String dataDir
        +int autosaveSeconds
    }
    class LaunchOptions {
        <<record>>
        +List~String~ springArguments
        +boolean helpRequested
        +parse(String... args)$ LaunchOptions
    }

    %% ---------- Exceptions ----------
    class BusinessRuleException {
        -HttpStatus status
    }
    class ResourceNotFoundException
    class DataFileException {
        -String fileName
        -int line
    }
    class InvalidLaunchOptionException
    class RuntimeException

    %% ---------- Relationships ----------
    Locatable <|.. Store
    Locatable <|.. GeoPoint
    Expirable <|.. FoodItem
    Expirable <|.. Order

    Store "0..*" --> "1" User : owner
    FoodItem "0..*" --> "1" Store : offered by
    Order "0..*" --> "1" User : placed by
    Order "0..*" --> "1" FoodItem : reserves
    FoodItemPhoto "0..1" --> "1" FoodItem : shows

    User --> UserRole
    User --> EligibilityStatus
    Order --> OrderType
    Order --> OrderStatus

    FoodItemService ..> GeoPoint : searches from
    FoodItemService ..> Order : creates
    OrderService ..> Order : cancels / hands over
    ReservationExpiryJob ..> Order : expires
    FileDataStore ..> CsvFormat : uses
    FileDataStore ..> CsvRecord : reads rows as
    FileDataStore --> StorageProperties
    LaunchOptions ..> StorageProperties : sets mode and folder

    BusinessRuleException --|> RuntimeException
    ResourceNotFoundException --|> RuntimeException
    DataFileException --|> RuntimeException
    InvalidLaunchOptionException --|> RuntimeException
    FoodItemService ..> BusinessRuleException : throws
    OrderService ..> ResourceNotFoundException : throws
    CsvRecord ..> DataFileException : throws
    LaunchOptions ..> InvalidLaunchOptionException : throws
```

## Where each requirement is met

| Requirement | In the code |
|---|---|
| ≥ 4 classes | `User`, `Store`, `FoodItem`, `Order`, `FoodItemPhoto`, `GeoPoint`, the services, `FileDataStore`, ... |
| ≥ 2 interfaces | `Locatable` (`Store`, `GeoPoint`), `Expirable` (`FoodItem`, `Order`) |
| ≥ 2 collections | `GeocodingService.cache` (`LinkedHashMap` of `List`), `FileDataStore.writtenFiles` / `writtenPhotos` (`HashMap`), the `HashSet`s of known ids in `FileDataStore.load()`, `LaunchOptions.springArguments` (`List`) |
| Files | `FileDataStore` writes `data/*.csv` + `data/photos/` and reads them back at startup |
| Config / arguments | `application.yml`, `application-files.yml`, `application-postgres.yml`; `--mode=demo/files`, `--data-dir=`, `--port=` (`LaunchOptions`) |
| Java exceptions handled | `IOException`, `NoSuchFileException`, `MalformedInputException`, `AtomicMoveNotSupportedException`, `InvalidPathException` (`FileDataStore`, `LaunchOptions`); `NumberFormatException`, `DateTimeParseException`, `IllegalArgumentException` (`CsvRecord`, `LaunchOptions`); `InterruptedException` (`GeocodingService`) |
| Custom exceptions | `BusinessRuleException`, `ResourceNotFoundException`, `DataFileException`, `InvalidLaunchOptionException` |
| Input validation | Bean Validation on every request body (`dto/*Request`) and query parameter; settings checked at startup (`@Validated` on `FoodRescueProperties`, `StorageProperties`); program arguments (`LaunchOptions`); every value read from a file (`CsvRecord`, `FileDataStore`) |
