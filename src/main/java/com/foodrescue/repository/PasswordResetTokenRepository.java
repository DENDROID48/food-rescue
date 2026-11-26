package com.foodrescue.repository;

import com.foodrescue.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** How many links were asked for recently, so one inbox cannot be flooded. */
    long countByUserIdAndCreatedAtAfter(Long userId, Instant since);

    /** After a successful reset, the other links of that person stop working too. */
    @Modifying
    @Query("delete from PasswordResetToken t where t.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("delete from PasswordResetToken t where t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
