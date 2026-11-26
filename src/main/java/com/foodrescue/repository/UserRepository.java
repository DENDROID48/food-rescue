package com.foodrescue.repository;

import com.foodrescue.domain.EligibilityStatus;
import com.foodrescue.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data generates the implementation of this interface at startup.
 * {@link JpaRepository} already provides save, findById, findAll, delete and paging.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** Derived query: Spring builds "select u from User u where u.email = ?1" from the method name. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    long countByRoleAndDeletedAtIsNull(com.foodrescue.domain.UserRole role);

    /** Verification requests waiting for an administrator, oldest first. */
    List<User> findByEligibilityStatusOrderByCreatedAtAsc(EligibilityStatus status);

    /**
     * Loads one user and locks the row until the current transaction ends (SELECT ... FOR UPDATE).
     * Used by free claims so that two simultaneous requests from the same user run one after
     * the other, and the second one sees the first claim when it checks the daily limit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
