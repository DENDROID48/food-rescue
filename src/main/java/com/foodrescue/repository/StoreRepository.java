package com.foodrescue.repository;

import com.foodrescue.domain.Store;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoreRepository extends JpaRepository<Store, Long> {

    /** All stores managed by one owner. */
    List<Store> findByOwnerId(Long ownerId);

    /** Every store with its owner loaded in the same query (admin overview). */
    @EntityGraph(attributePaths = "owner")
    List<Store> findAllByOrderByNameAsc();
}
