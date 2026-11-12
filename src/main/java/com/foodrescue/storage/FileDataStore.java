package com.foodrescue.storage;

import com.foodrescue.config.StorageProperties;
import com.foodrescue.domain.ConsumerCategory;
import com.foodrescue.domain.EligibilityStatus;
import com.foodrescue.domain.FoodItemPhoto;
import com.foodrescue.domain.OrderStatus;
import com.foodrescue.domain.OrderType;
import com.foodrescue.domain.UserRole;
import com.foodrescue.exception.DataFileException;
import com.foodrescue.repository.FoodItemPhotoRepository;
import com.foodrescue.repository.FoodItemRepository;
import com.foodrescue.repository.OrderRepository;
import com.foodrescue.repository.StoreRepository;
import com.foodrescue.repository.UserRepository;
import com.foodrescue.service.FoodItemPhotoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Keeps all data in CSV files, so it survives a restart. Active only with {@code --mode=files}.
 *
 * <h3>Files</h3>
 * One file per kind of data, in the data folder: {@code users.csv}, {@code stores.csv},
 * {@code food-items.csv}, {@code photos.csv} and {@code orders.csv}, plus the photo images in
 * {@code photos/}. They open in any spreadsheet program.
 *
 * <h3>When</h3>
 * <ul>
 *   <li><b>Start:</b> the files are read, every value is checked, and the rows are put into the
 *       database the rest of the application works with. A bad value stops the start with a
 *       message naming the file and line ({@link DataFileException}).</li>
 *   <li><b>While running:</b> every few seconds the data is written back, but only the files
 *       whose contents changed.</li>
 *   <li><b>Stop:</b> the data is written one last time.</li>
 * </ul>
 * Each file is first written under a temporary name and then renamed, so a crash in the middle of
 * writing never leaves half a file behind.
 */
@Component
@ConditionalOnProperty(prefix = "foodrescue.storage", name = "mode", havingValue = "files")
@Order(Ordered.HIGHEST_PRECEDENCE) // Load before the demo data and admin account are created.
@Slf4j
public class FileDataStore implements ApplicationRunner {

    static final String USERS = "users.csv";
    static final String STORES = "stores.csv";
    static final String FOOD_ITEMS = "food-items.csv";
    static final String PHOTOS = "photos.csv";
    static final String ORDERS = "orders.csv";
    static final String PHOTO_FOLDER = "photos";

    static final List<String> USER_COLUMNS = List.of(
            "id", "full_name", "email", "password_hash", "role", "category", "eligibility_status", "created_at");
    static final List<String> STORE_COLUMNS = List.of(
            "id", "name", "address", "latitude", "longitude", "active", "owner_id", "created_at");
    static final List<String> FOOD_ITEM_COLUMNS = List.of(
            "id", "store_id", "name", "description", "original_price", "discounted_price", "quantity_available",
            "pay_it_forward_quantity", "expires_at", "has_photo", "created_at", "version");
    static final List<String> PHOTO_COLUMNS = List.of("food_item_id", "content_type", "file");
    static final List<String> ORDER_COLUMNS = List.of(
            "id", "user_id", "food_item_id", "quantity", "total_price", "type", "status", "pickup_code",
            "pickup_deadline", "created_at", "version");

    private final Path dataDir;
    private final int autosaveSeconds;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;
    private final UserRepository userRepository;
    private final StoreRepository storeRepository;
    private final FoodItemRepository foodItemRepository;
    private final FoodItemPhotoRepository photoRepository;
    private final OrderRepository orderRepository;

    /** Contents last written to (or read from) each CSV file, so unchanged files are not rewritten. */
    private final Map<String, String> writtenFiles = new HashMap<>();
    /** Hash of each photo last written, by food item id. */
    private final Map<Long, Integer> writtenPhotos = new HashMap<>();
    /** False until loading and the startup data are done, so an early save cannot empty the files. */
    private volatile boolean active;

    public FileDataStore(StorageProperties properties,
                         JdbcTemplate jdbc,
                         PlatformTransactionManager transactionManager,
                         UserRepository userRepository,
                         StoreRepository storeRepository,
                         FoodItemRepository foodItemRepository,
                         FoodItemPhotoRepository photoRepository,
                         OrderRepository orderRepository) {
        this.dataDir = Path.of(properties.dataDir()).toAbsolutePath().normalize();
        this.autosaveSeconds = properties.autosaveSeconds();
        this.jdbc = jdbc;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
        this.userRepository = userRepository;
        this.storeRepository = storeRepository;
        this.foodItemRepository = foodItemRepository;
        this.photoRepository = photoRepository;
        this.orderRepository = orderRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        load();
    }

    // ------------------------------------------------------------------------------------
    // Reading the files at startup
    // ------------------------------------------------------------------------------------

    void load() {
        if (userRepository.count() > 0) {
            log.warn("The database already has data, so {} is not read; its files will be replaced by the database contents",
                    dataDir);
            return;
        }
        List<CsvRecord> users = read(USERS, USER_COLUMNS);
        List<CsvRecord> stores = read(STORES, STORE_COLUMNS);
        List<CsvRecord> items = read(FOOD_ITEMS, FOOD_ITEM_COLUMNS);
        List<CsvRecord> photos = read(PHOTOS, PHOTO_COLUMNS);
        List<CsvRecord> orders = read(ORDERS, ORDER_COLUMNS);
        if (users.isEmpty() && stores.isEmpty() && items.isEmpty() && orders.isEmpty()) {
            log.info("No saved data in {} yet; it will be created there", dataDir);
            return;
        }
        // Image files are read before the transaction, so no database work waits on the disk.
        Map<CsvRecord, byte[]> photoBytes = new LinkedHashMap<>();
        for (CsvRecord photo : photos) {
            photoBytes.put(photo, readPhoto(photo));
        }

        try {
            writeTransaction.executeWithoutResult(status -> {
                Set<Long> userIds = new HashSet<>();
                Set<String> emails = new HashSet<>();
                users.forEach(row -> insertUser(row, userIds, emails));
                Set<Long> storeIds = new HashSet<>();
                stores.forEach(row -> insertStore(row, storeIds, userIds));
                Set<Long> itemIds = new HashSet<>();
                items.forEach(row -> insertFoodItem(row, itemIds, storeIds));
                Set<Long> photoIds = new HashSet<>();
                photoBytes.forEach((row, data) -> insertPhoto(row, data, photoIds, itemIds));
                Set<Long> orderIds = new HashSet<>();
                orders.forEach(row -> insertOrder(row, orderIds, userIds, itemIds));

                // An item counts as having a photo exactly when photos.csv lists one for it.
                jdbc.update("update food_item set has_photo = exists "
                        + "(select 1 from food_item_photo p where p.food_item_id = food_item.id)");
                // New rows get ids after the highest loaded one, not ids that are already used.
                for (String table : List.of("app_user", "store", "food_item", "orders")) {
                    continueIdsAfterHighest(table);
                }
            });
        } catch (DataAccessException ex) {
            throw new DataFileException(dataDir.toString(), 0,
                    "the database refused the data: " + ex.getMostSpecificCause().getMessage(), ex);
        }
        log.info("Loaded {} users, {} stores, {} food items, {} photos and {} orders from {}",
                users.size(), stores.size(), items.size(), photos.size(), orders.size(), dataDir);
    }

    /** The data lines of one file, checked against the expected columns; empty when the file does not exist. */
    private List<CsvRecord> read(String fileName, List<String> columns) {
        String text;
        try {
            text = Files.readString(dataDir.resolve(fileName), StandardCharsets.UTF_8);
        } catch (NoSuchFileException ex) {
            return List.of();
        } catch (MalformedInputException ex) {
            throw new DataFileException(fileName, 0, "is not UTF-8 text", ex);
        } catch (IOException ex) {
            throw new DataFileException(fileName, 0, "cannot be read: " + ex.getMessage(), ex);
        }

        List<CsvFormat.Row> rows = CsvFormat.parse(text, fileName);
        if (rows.isEmpty()) {
            return List.of();
        }
        CsvFormat.Row headerRow = rows.get(0);
        Map<String, Integer> header = new HashMap<>();
        for (int i = 0; i < headerRow.values().size(); i++) {
            header.put(headerRow.values().get(i).strip(), i);
        }
        if (header.size() != headerRow.values().size() || !header.keySet().equals(new HashSet<>(columns))) {
            throw new DataFileException(fileName, headerRow.line(),
                    "the first line must name the columns " + String.join(",", columns));
        }
        writtenFiles.put(fileName, text);
        return rows.stream().skip(1).map(row -> new CsvRecord(fileName, row, header)).toList();
    }

    private byte[] readPhoto(CsvRecord row) {
        String fileName = row.text("file", 100);
        Path folder = dataDir.resolve(PHOTO_FOLDER);
        Path path;
        try {
            path = folder.resolve(fileName).normalize();
        } catch (InvalidPathException ex) {
            throw row.error("'file' is not a valid file name: " + fileName);
        }
        // Refuses names like "../users.csv" that point outside the photos folder.
        if (!folder.equals(path.getParent())) {
            throw row.error("'file' must be the name of a file in the " + PHOTO_FOLDER + " folder");
        }
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (NoSuchFileException ex) {
            throw row.error("the photo " + PHOTO_FOLDER + "/" + fileName + " does not exist");
        } catch (IOException ex) {
            throw row.error("the photo " + fileName + " cannot be read: " + ex.getMessage());
        }
        String detectedType = FoodItemPhotoService.detectImageType(data);
        if (detectedType == null || !detectedType.equals(row.text("content_type", 50))) {
            throw row.error("the photo " + fileName + " is not a " + row.text("content_type", 50) + " image");
        }
        writtenPhotos.put(row.id("food_item_id"), Arrays.hashCode(data));
        return data;
    }

    private void insertUser(CsvRecord row, Set<Long> userIds, Set<String> emails) {
        long id = unique(row, "id", userIds);
        String email = row.text("email", 180).toLowerCase(Locale.ROOT);
        if (!email.matches("[^@\\s]+@[^@\\s]+")) {
            throw row.error("'email' is not an email address: " + email);
        }
        if (!emails.add(email)) {
            throw row.error("the email " + email + " is used by two users");
        }
        jdbc.update("insert into app_user (id, full_name, email, password_hash, role, category, eligibility_status, "
                        + "created_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                id, row.text("full_name", 120), email, row.text("password_hash", 100),
                row.enumValue("role", UserRole.class).name(),
                row.enumValue("category", ConsumerCategory.class).name(),
                row.enumValue("eligibility_status", EligibilityStatus.class).name(),
                moment(row.instant("created_at")));
    }

    private void insertStore(CsvRecord row, Set<Long> storeIds, Set<Long> userIds) {
        long id = unique(row, "id", storeIds);
        jdbc.update("insert into store (id, name, address, latitude, longitude, active, owner_id, created_at) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                id, row.text("name", 150), row.text("address", 255),
                row.coordinate("latitude", 90), row.coordinate("longitude", 180), row.bool("active"),
                reference(row, "owner_id", userIds, USERS), moment(row.instant("created_at")));
    }

    private void insertFoodItem(CsvRecord row, Set<Long> itemIds, Set<Long> storeIds) {
        long id = unique(row, "id", itemIds);
        BigDecimal originalPrice = row.money("original_price");
        BigDecimal discountedPrice = row.money("discounted_price");
        if (discountedPrice.compareTo(originalPrice) >= 0) {
            throw row.error("'discounted_price' must be lower than 'original_price'");
        }
        String description = row.optionalText("description");
        if (description != null && description.length() > 500) {
            throw row.error("'description' is longer than 500 characters");
        }
        jdbc.update("insert into food_item (id, store_id, name, description, original_price, discounted_price, "
                        + "quantity_available, pay_it_forward_quantity, expires_at, has_photo, created_at, version) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, reference(row, "store_id", storeIds, STORES), row.text("name", 150), description,
                originalPrice, discountedPrice, row.count("quantity_available"), row.count("pay_it_forward_quantity"),
                moment(row.instant("expires_at")), row.bool("has_photo"), moment(row.instant("created_at")),
                row.optionalLong("version"));
    }

    private void insertPhoto(CsvRecord row, byte[] data, Set<Long> photoIds, Set<Long> itemIds) {
        long itemId = unique(row, "food_item_id", photoIds);
        jdbc.update("insert into food_item_photo (food_item_id, content_type, data) values (?, ?, ?)",
                reference(row, "food_item_id", itemIds, FOOD_ITEMS), row.text("content_type", 50), data);
        log.debug("Loaded the photo of food item {}", itemId);
    }

    private void insertOrder(CsvRecord row, Set<Long> orderIds, Set<Long> userIds, Set<Long> itemIds) {
        long id = unique(row, "id", orderIds);
        int quantity = row.count("quantity");
        if (quantity < 1) {
            throw row.error("'quantity' must be at least 1");
        }
        String pickupCode = row.text("pickup_code", 10);
        if (!pickupCode.matches("\\d{6}")) {
            throw row.error("'pickup_code' must be 6 digits, not '" + pickupCode + "'");
        }
        jdbc.update("insert into orders (id, user_id, food_item_id, quantity, total_price, type, status, pickup_code, "
                        + "pickup_deadline, created_at, version) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, reference(row, "user_id", userIds, USERS), reference(row, "food_item_id", itemIds, FOOD_ITEMS),
                quantity, row.money("total_price"), row.enumValue("type", OrderType.class).name(),
                row.enumValue("status", OrderStatus.class).name(), pickupCode,
                moment(row.instant("pickup_deadline")), moment(row.instant("created_at")),
                row.optionalLong("version"));
    }

    private static long unique(CsvRecord row, String column, Set<Long> seen) {
        long id = row.id(column);
        if (!seen.add(id)) {
            throw row.error("'" + column + "' " + id + " appears twice");
        }
        return id;
    }

    private static long reference(CsvRecord row, String column, Set<Long> known, String otherFile) {
        long id = row.id(column);
        if (!known.contains(id)) {
            throw row.error("'" + column + "' " + id + " does not exist in " + otherFile);
        }
        return id;
    }

    private static OffsetDateTime moment(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private void continueIdsAfterHighest(String table) {
        Long next = jdbc.queryForObject("select coalesce(max(id), 0) + 1 from " + table, Long.class);
        jdbc.execute("alter table " + table + " alter column id restart with " + next);
    }

    // ------------------------------------------------------------------------------------
    // Writing the files
    // ------------------------------------------------------------------------------------

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        active = true;
        save();
        log.info("Data is kept in {} (saved every {} s and when the program stops)", dataDir, autosaveSeconds);
    }

    @Scheduled(fixedDelayString = "${foodrescue.storage.autosave-seconds:10}", timeUnit = TimeUnit.SECONDS)
    public void autosave() {
        save();
    }

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        if (active) {
            save();
            active = false;
            log.info("Saved the data to {}", dataDir);
        }
    }

    /** Writes every file whose contents changed since the last save. Errors are logged, not thrown, and retried next time. */
    public synchronized void save() {
        if (!active) {
            return;
        }
        Snapshot snapshot = readTransaction.execute(status -> takeSnapshot());
        try {
            Files.createDirectories(dataDir.resolve(PHOTO_FOLDER));
            writePhotos(Objects.requireNonNull(snapshot).photos());
            for (Map.Entry<String, String> file : snapshot.files().entrySet()) {
                writeIfChanged(file.getKey(), file.getValue());
            }
        } catch (IOException ex) {
            log.error("Could not save the data to {}: {}. Trying again at the next save.", dataDir, ex.toString());
        }
    }

    /** Everything to write, read in one transaction so the files agree with each other. */
    private record Snapshot(Map<String, String> files, List<FoodItemPhoto> photos) {
    }

    private Snapshot takeSnapshot() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(USERS, csv(USER_COLUMNS, userRepository.findAll(Sort.by("id")), user -> Arrays.asList(
                user.getId(), user.getFullName(), user.getEmail(), user.getPasswordHash(), user.getRole(),
                user.getCategory(), user.getEligibilityStatus(), user.getCreatedAt())));
        files.put(STORES, csv(STORE_COLUMNS, storeRepository.findAll(Sort.by("id")), store -> Arrays.asList(
                store.getId(), store.getName(), store.getAddress(), store.getLatitude(), store.getLongitude(),
                store.isActive(), store.getOwner().getId(), store.getCreatedAt())));
        files.put(FOOD_ITEMS, csv(FOOD_ITEM_COLUMNS, foodItemRepository.findAll(Sort.by("id")), item -> Arrays.asList(
                item.getId(), item.getStore().getId(), item.getName(), item.getDescription(),
                item.getOriginalPrice().toPlainString(), item.getDiscountedPrice().toPlainString(),
                item.getQuantityAvailable(), item.getPayItForwardQuantity(), item.getExpiresAt(), item.isHasPhoto(),
                item.getCreatedAt(), item.getVersion())));
        List<FoodItemPhoto> photos = photoRepository.findAll(Sort.by("foodItemId"));
        files.put(PHOTOS, csv(PHOTO_COLUMNS, photos, photo -> Arrays.asList(
                photo.getFoodItemId(), photo.getContentType(), photoFileName(photo))));
        files.put(ORDERS, csv(ORDER_COLUMNS, orderRepository.findAll(Sort.by("id")), order -> Arrays.asList(
                order.getId(), order.getUser().getId(), order.getFoodItem().getId(), order.getQuantity(),
                order.getTotalPrice().toPlainString(), order.getType(), order.getStatus(), order.getPickupCode(),
                order.getPickupDeadline(), order.getCreatedAt(), order.getVersion())));
        return new Snapshot(files, photos);
    }

    private static <T> String csv(List<String> columns, List<T> rows, Function<T, List<?>> toValues) {
        StringBuilder out = new StringBuilder(CsvFormat.line(columns));
        for (T row : rows) {
            out.append(CsvFormat.line(toValues.apply(row)));
        }
        return out.toString();
    }

    private void writeIfChanged(String fileName, String contents) throws IOException {
        if (contents.equals(writtenFiles.get(fileName))) {
            return;
        }
        writeAtomically(dataDir.resolve(fileName), contents.getBytes(StandardCharsets.UTF_8));
        writtenFiles.put(fileName, contents);
    }

    private void writePhotos(List<FoodItemPhoto> photos) throws IOException {
        Path folder = dataDir.resolve(PHOTO_FOLDER);
        Set<String> current = new HashSet<>();
        for (FoodItemPhoto photo : photos) {
            String fileName = photoFileName(photo);
            current.add(fileName);
            Path target = folder.resolve(fileName);
            int hash = Arrays.hashCode(photo.getData());
            if (!Objects.equals(writtenPhotos.get(photo.getFoodItemId()), hash) || Files.notExists(target)) {
                writeAtomically(target, photo.getData());
                writtenPhotos.put(photo.getFoodItemId(), hash);
            }
        }
        // Removes images of photos that were deleted or replaced by another image type.
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path file : files) {
                if (!current.contains(file.getFileName().toString())) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    static String photoFileName(FoodItemPhoto photo) {
        String extension = switch (photo.getContentType()) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".img";
        };
        return photo.getFoodItemId() + extension;
    }

    /** Writes to a temporary file first and then renames it, so the real file is never half written. */
    private static void writeAtomically(Path target, byte[] data) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, data);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            // Some file systems cannot rename atomically; a plain replace is the next best thing.
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
