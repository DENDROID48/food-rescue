package com.foodrescue.domain;

import com.foodrescue.storage.PhotoFolder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One test per constructor of the domain classes: the hand-written ones keep their arguments,
 * and the no-argument constructors JPA uses start every object in a valid, safe state.
 */
class ConstructorsTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

    // ---------------- hand-written constructors ----------------

    @Test
    void favouriteStoreConstructorKeepsWhoFollowsWhichShopAndWhen() {
        FavouriteStore f = new FavouriteStore(7L, 3L, NOW);
        assertThat(f.getUserId()).isEqualTo(7L);
        assertThat(f.getStoreId()).isEqualTo(3L);
        assertThat(f.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void favouriteStoreKeyConstructors() {
        assertThat(new FavouriteStore.Key(7L, 3L)).isEqualTo(new FavouriteStore.Key(7L, 3L));
        FavouriteStore.Key empty = new FavouriteStore.Key();   // needed by JPA
        assertThat(empty.userId()).isNull();
        assertThat(empty.storeId()).isNull();
    }

    @Test
    void favouriteProductConstructorKeepsWhoFollowsWhichProductAndWhen() {
        FavouriteProduct f = new FavouriteProduct(7L, 12L, NOW);
        assertThat(f.getUserId()).isEqualTo(7L);
        assertThat(f.getTemplateId()).isEqualTo(12L);
        assertThat(f.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void favouriteProductKeyConstructors() {
        assertThat(new FavouriteProduct.Key(7L, 12L).templateId()).isEqualTo(12L);
        FavouriteProduct.Key empty = new FavouriteProduct.Key();
        assertThat(empty.userId()).isNull();
        assertThat(empty.templateId()).isNull();
    }

    @Test
    void photoFolderConstructorGivesEachKindItsOwnFolder() {
        assertThat(PhotoFolder.FOOD_ITEMS.folderName()).isEqualTo("food-items");
        assertThat(PhotoFolder.TEMPLATES.folderName()).isEqualTo("templates");
    }

    // ---------------- no-argument constructors: safe defaults ----------------

    @Test
    void newUserIsACustomerWhoCannotClaimFreeFoodYet() {
        User u = new User();
        assertThat(u.getRole()).isEqualTo(UserRole.CONSUMER);
        assertThat(u.getEligibilityStatus()).isEqualTo(EligibilityStatus.NOT_REQUESTED);
        assertThat(u.isFreeClaimEligible()).isFalse();
        assertThat(u.getCreatedAt()).isNotNull();
    }

    @Test
    void newStoreIsActiveInTheDefaultTimeZone() {
        Store s = new Store();
        assertThat(s.isActive()).isTrue();
        assertThat(s.getTimeZone()).isEqualTo(Store.DEFAULT_TIME_ZONE);
        assertThat(s.getReminderTime()).isNull();
    }

    @Test
    void newFoodItemIsAnActiveSingleProductWithoutTags() {
        FoodItem item = new FoodItem();
        assertThat(item.getKind()).isEqualTo(OfferKind.PRODUCT);
        assertThat(item.getStatus()).isEqualTo(OfferStatus.ACTIVE);
        assertThat(item.getTags()).isEmpty();
        assertThat(item.isRescueDrop()).isFalse();
        assertThat(item.isHasPhoto()).isFalse();
    }

    @Test
    void newProductTemplateIsASingleProductThatIsNotArchived() {
        ProductTemplate t = new ProductTemplate();
        assertThat(t.getKind()).isEqualTo(OfferKind.PRODUCT);
        assertThat(t.isArchived()).isFalse();
        assertThat(t.getTags()).isEmpty();
    }

    @Test
    void newOrderStartsAsReserved() {
        Order o = new Order();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.RESERVED);
        assertThat(o.getCreatedAt()).isNotNull();
        assertThat(o.getReminderSentAt()).isNull();
    }

    @Test
    void newRadarProfileIsPersonalisedWithRemindersOn() {
        RadarProfile p = new RadarProfile();
        assertThat(p.isPersonalised()).isTrue();
        assertThat(p.isPickupReminders()).isTrue();
        assertThat(p.getInterests()).isEmpty();
        assertThat(p.getTimeZone()).isEqualTo(Store.DEFAULT_TIME_ZONE);
    }

    @Test
    void newNotificationIsAnUnreadDropAlert() {
        Notification n = new Notification();
        assertThat(n.getKind()).isEqualTo(NotificationKind.DROP);
        assertThat(n.getReadAt()).isNull();
    }

    @Test
    void otherEntitiesStartEmpty() {
        assertThat(new DropAlertPreference().isEnabled()).isFalse();
        assertThat(new BundleComponent().getUnits()).isZero();
        assertThat(new FoodItemPhoto().getContentType()).isNull();
        assertThat(new ProductTemplatePhoto().getContentType()).isNull();
        assertThat(new com.foodrescue.domain.PushSubscription().getFailures()).isZero();
        FoodItem priced = new FoodItem();
        priced.setOriginalPrice(new BigDecimal("10.00"));
        assertThat(priced.getOriginalPrice()).isEqualByComparingTo("10");
    }
}
