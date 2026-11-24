package com.foodrescue.repository;

import com.foodrescue.domain.DropAlertPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DropAlertPreferenceRepository extends JpaRepository<DropAlertPreference, Long> {

    /** Coarse rectangle filter; the service then checks each customer's own radius exactly. */
    @Query("""
            select p from DropAlertPreference p
            where p.enabled = true
              and p.latitude between :minLat and :maxLat
              and p.longitude between :minLng and :maxLng
            """)
    List<DropAlertPreference> findEnabledInBox(@Param("minLat") double minLat, @Param("maxLat") double maxLat,
                                               @Param("minLng") double minLng, @Param("maxLng") double maxLng);
}
