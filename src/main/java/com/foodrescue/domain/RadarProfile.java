package com.foodrescue.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Set;

/**
 * A customer's Rescue Radar settings. Without a row, the defaults apply: personalised
 * recommendations on, no interests, no favourite alerts, no digest, no quiet hours.
 */
@Entity
@Table(name = "radar_profile")
@Getter
@Setter
@NoArgsConstructor
public class RadarProfile {

    @Id
    private Long userId;

    /** False turns recommendations based on the customer's own activity off. */
    @Column(nullable = false)
    private boolean personalised = true;

    @Convert(converter = FoodTagsConverter.class)
    @Column(nullable = false, length = 200)
    private Set<FoodTag> interests = EnumSet.noneOf(FoodTag.class);

    /** Alert when a favourite product is on offer, or a favourite shop launches a drop. */
    @Column(nullable = false)
    private boolean notifyFavourites;

    /** A reminder shortly before a reservation's pickup closes. On unless the customer turns it off. */
    @Column(nullable = false)
    private boolean pickupReminders = true;

    /** Show the offer to get verified for free items on Browse. The customer can turn it off ("don't ask again"). */
    @Column(nullable = false)
    private boolean freeItemsOffer = true;

    /** No push messages between these times (alerts still land in the inbox). May span midnight. */
    private LocalTime quietStart;
    private LocalTime quietEnd;

    /** Daily summary of what is near the customer's alert place, at this local time; null means off. */
    private LocalTime digestTime;

    private LocalDate lastDigestOn;

    /** The customer's own time zone, for quiet hours and the digest. */
    @Column(nullable = false, length = 40)
    private String timeZone = Store.DEFAULT_TIME_ZONE;

    @Column(nullable = false)
    private Instant updatedAt;

    public ZoneId zone() {
        return ZoneId.of(timeZone);
    }

    /** True when the local time falls in the quiet hours, including windows like 22:00–07:00. */
    public boolean isQuietAt(Instant now) {
        if (quietStart == null || quietEnd == null || quietStart.equals(quietEnd)) {
            return false;
        }
        LocalTime t = LocalTime.ofInstant(now, zone());
        return quietStart.isBefore(quietEnd)
                ? !t.isBefore(quietStart) && t.isBefore(quietEnd)
                : !t.isBefore(quietStart) || t.isBefore(quietEnd);
    }
}
