package com.foodrescue.repository;

import com.foodrescue.domain.FavouriteProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface FavouriteProductRepository extends JpaRepository<FavouriteProduct, FavouriteProduct.Key> {

    List<FavouriteProduct> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("select f.templateId from FavouriteProduct f where f.userId = :userId")
    Set<Long> templateIdsOf(@Param("userId") Long userId);

    /** Who likes which of these products: rows of [userId, templateId]. */
    @Query("select f.userId, f.templateId from FavouriteProduct f where f.templateId in :templateIds")
    List<Object[]> followersOf(@Param("templateIds") Collection<Long> templateIds);
}
