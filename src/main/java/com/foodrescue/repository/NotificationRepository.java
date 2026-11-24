package com.foodrescue.repository;

import com.foodrescue.domain.Notification;
import com.foodrescue.domain.NotificationKind;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @EntityGraph(attributePaths = {"foodItem", "store"})
    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable page);

    long countByUserIdAndReadAtIsNull(Long userId);

    /** Users (among the candidates) and how many alerts of the given kinds they received since a moment: for the daily cap. */
    @Query("""
            select n.userId, count(n) from Notification n
            where n.userId in :users and n.createdAt >= :since and n.kind in :kinds group by n.userId
            """)
    List<Object[]> countSince(@Param("users") Collection<Long> users, @Param("since") Instant since,
                              @Param("kinds") Collection<NotificationKind> kinds);

    /** Users (among the candidates) and how many of their recent alerts of these kinds are unread: for the back-off. */
    @Query("""
            select n.userId, count(n) from Notification n
            where n.userId in :users and n.createdAt >= :since and n.kind in :kinds and n.readAt is null group by n.userId
            """)
    List<Object[]> unreadSince(@Param("users") Collection<Long> users, @Param("since") Instant since,
                               @Param("kinds") Collection<NotificationKind> kinds);

    /** [userId, foodItemId] pairs already notified about these offers, so nobody hears about the same offer twice. */
    @Query("select n.userId, n.foodItem.id from Notification n where n.foodItem.id in :itemIds")
    List<Object[]> notifiedAbout(@Param("itemIds") Collection<Long> itemIds);

    /** Users (among the candidates) already alerted about this store since a moment: one alert per store at a time. */
    @Query("select distinct n.userId from Notification n where n.userId in :users and n.store.id = :storeId and n.createdAt >= :since")
    Set<Long> alertedAboutStoreSince(@Param("users") Collection<Long> users, @Param("storeId") Long storeId,
                                     @Param("since") Instant since);

    @Modifying
    @Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);

    @Modifying
    @Query("delete from Notification n where n.createdAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
