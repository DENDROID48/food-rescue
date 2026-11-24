package com.foodrescue.repository;

import com.foodrescue.domain.FavouriteStore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface FavouriteStoreRepository extends JpaRepository<FavouriteStore, FavouriteStore.Key> {

    List<FavouriteStore> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("select f.storeId from FavouriteStore f where f.userId = :userId")
    Set<Long> storeIdsOf(@Param("userId") Long userId);

    @Query("select f.userId from FavouriteStore f where f.storeId = :storeId")
    Set<Long> followersOf(@Param("storeId") Long storeId);
}
