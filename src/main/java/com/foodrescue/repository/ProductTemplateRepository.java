package com.foodrescue.repository;

import com.foodrescue.domain.ProductTemplate;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductTemplateRepository extends JpaRepository<ProductTemplate, Long> {

    /** The store's products, alphabetically; archived ones are hidden. */
    List<ProductTemplate> findByStoreIdAndArchivedFalseOrderByNameAsc(Long storeId);

    /**
     * Loads and locks a template (SELECT ... FOR UPDATE). Publishing locks it, so two publish
     * requests for the same product run one after the other and the second sees the first offer.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ProductTemplate t join fetch t.store where t.id = :id")
    Optional<ProductTemplate> findByIdForUpdate(@Param("id") Long id);
}
