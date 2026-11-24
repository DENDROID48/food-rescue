# Food Rescue: class diagram

The application's own classes, interfaces and the relationships between them. Spring's generated
repository interfaces, the JSON request/response records and configuration classes are left out
so the picture stays readable. (Opens as a picture in IntelliJ's Markdown preview, on GitHub, or
when pasted into https://mermaid.live, which can also export it as PNG or SVG.)

```mermaid
classDiagram
    direction TB

    %% ---------------- Interfaces ----------------
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
    class PhotoStorage {
        <<interface>>
        +save(PhotoFolder folder, long id, byte[] data)
        +load(PhotoFolder folder, long id) byte[]
        +copy(PhotoFolder from, long fromId, PhotoFolder to, long toId) boolean
        +delete(PhotoFolder folder, long id)
    }

    %% ---------------- Domain ----------------
    class User {
        -Long id
        -String fullName
        -String email
        -String passwordHash
        -UserRole role
        -EligibilityStatus eligibilityStatus
        +isFreeClaimEligible() boolean
    }
    class Store {
        -Long id
        -String name
        -String address
        -double latitude
        -double longitude
        -boolean active
        -String timeZone
        +zone() ZoneId
    }
    class GeoPoint {
        <<record>>
        +double latitude
        +double longitude
    }
    class ProductTemplate {
        -Long id
        -String name
        -OfferKind kind
        -BigDecimal originalPrice
        -BigDecimal discountedPrice
        -LocalTime pickupStart
        -LocalTime pickupEnd
        -Integer unitWeightGrams
        -Set~FoodTag~ tags
        -boolean archived
    }
    class FoodItem {
        -Long id
        -String name
        -BigDecimal originalPrice
        -BigDecimal discountedPrice
        -int quantityAvailable
        -int payItForwardQuantity
        -Instant pickupStartsAt
        -Instant expiresAt
        -OfferKind kind
        -OfferStatus status
        -boolean rescueDrop
        -Set~FoodTag~ tags
        +expiryMoment() Instant
        +returnStock(OrderType type, int quantity)
        +state(Instant now) OfferState
        +discountPercent() int
    }
    class BundleComponent {
        -Long id
        -int units
    }
    class Order {
        -Long id
        -int quantity
        -BigDecimal totalPrice
        -OrderType type
        -OrderStatus status
        -String pickupCode
        -Instant pickupDeadline
        -Instant reminderSentAt
        +expiryMoment() Instant
    }
    class DropAlertPreference {
        -Long userId
        -boolean enabled
        -double latitude
        -double longitude
        -double radiusKm
        -int maxPerDay
    }
    class RadarProfile {
        -Long userId
        -boolean personalised
        -Set~FoodTag~ interests
        -boolean pickupReminders
        -LocalTime quietStart
        -LocalTime quietEnd
        +isQuietAt(Instant now) boolean
    }
    class Notification {
        -Long id
        -Long userId
        -NotificationKind kind
        -String title
        -String body
        -Instant readAt
    }

    %% ---------------- Enums ----------------
    class UserRole {
        <<enumeration>>
        CONSUMER
        STORE_OWNER
        ADMIN
    }
    class OrderStatus {
        <<enumeration>>
        RESERVED
        PICKED_UP
        CANCELLED
        EXPIRED
    }
    class OfferStatus {
        <<enumeration>>
        DRAFT
        ACTIVE
        PAUSED
    }
    class FoodTag {
        <<enumeration>>
        BAKERY
        FRUIT_VEG
        READY_MEALS
        DAIRY
        MEAT_FISH
        SWEETS
        GROCERIES
        VEGETARIAN
        VEGAN
        +isDietary() boolean
    }
    class PhotoFolder {
        <<enumeration>>
        FOOD_ITEMS
        TEMPLATES
        +folderName() String
    }

    %% ---------------- Services ----------------
    class FoodItemService {
        +findAvailableNearby(double lat, double lng, double radiusKm) List~NearbyFoodItemResponse~
        +reserve(Long userId, Long itemId, int quantity) OrderResponse
        +claimPayItForward(Long userId, Long itemId) OrderResponse
    }
    class OrderService {
        +cancel(Long userId, Long orderId) OrderResponse
        +confirmPickup(Long ownerId, Long storeId, String code) OrderResponse
    }
    class AlertService {
        +sendPickupReminders()
        -nearby(Store store) List~DropAlertPreference~
    }
    class FoodItemPhotoService {
        +upload(Long ownerId, Long storeId, Long itemId, byte[] data)
        +get(Long itemId) PhotoFile
        +delete(Long ownerId, Long storeId, Long itemId)
    }
    class FilePhotoStorage {
        -Path root
    }
    class AnalyticsService {
        +insights(Long ownerId, Long storeId, int days) StoreInsights
        +exportCsv(Long ownerId, Long storeId, int days, Writer out)
    }

    %% ---------------- Exceptions ----------------
    class BusinessRuleException {
        -HttpStatus status
    }
    class ResourceNotFoundException
    class RuntimeException

    %% ---------------- Relationships ----------------
    Locatable <|.. Store
    Locatable <|.. GeoPoint
    Locatable <|.. DropAlertPreference
    Expirable <|.. FoodItem
    Expirable <|.. Order
    PhotoStorage <|.. FilePhotoStorage

    User "1" <-- "0..*" Store : owner
    Store "1" <-- "0..*" ProductTemplate : store
    Store "1" <-- "0..*" FoodItem : store
    ProductTemplate "0..1" <-- "0..*" FoodItem : made from
    FoodItem "1" <-- "0..*" BundleComponent : bundle
    FoodItem "1" <-- "0..*" BundleComponent : source
    User "1" <-- "0..*" Order : customer
    FoodItem "1" <-- "0..*" Order : reserves
    Store "0..1" <-- "0..*" Notification : about

    User --> UserRole
    Order --> OrderStatus
    FoodItem --> OfferStatus
    FoodItem --> FoodTag : Set
    ProductTemplate --> FoodTag : Set
    RadarProfile --> FoodTag : Set

    FoodItemService ..> GeoPoint : search point
    FoodItemService ..> FoodItem : reserves
    OrderService ..> Expirable : deadline check
    AlertService ..> Locatable : distance
    AlertService ..> RadarProfile
    FoodItemPhotoService --> PhotoStorage
    FilePhotoStorage ..> PhotoFolder
    AnalyticsService ..> Order : JDBC queries

    RuntimeException <|-- BusinessRuleException
    RuntimeException <|-- ResourceNotFoundException
    FoodItemService ..> BusinessRuleException : throws
    FilePhotoStorage ..> ResourceNotFoundException : throws
```

## Reading guide

| Arrow | Meaning | Example |
|---|---|---|
| `<\|..` dashed, hollow head | implements an interface | `Store` implements `Locatable` |
| `<\|--` solid, hollow head | extends a class | `BusinessRuleException` extends `RuntimeException` |
| `<--` with numbers | association (a field), with multiplicity | many `Order`s point to one `FoodItem` |
| `-->` | has a field of that type | `Order` has an `OrderStatus` |
| `..>` dashed | uses / depends on | `FoodItemService` creates a `GeoPoint` to measure distances |

**Interfaces (3, all specific to this app):** `Locatable`, `Expirable`, `PhotoStorage`.

**Collections:** `Set<FoodTag>` (an `EnumSet`) in `FoodItem`, `ProductTemplate` and `RadarProfile`;
`List<...>` results from the services; the address cache `Map<String, List<PlaceResponse>>` in
`GeocodingService`.
