package com.foodrescue.repository;

import com.foodrescue.domain.RadarProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RadarProfileRepository extends JpaRepository<RadarProfile, Long> {

    List<RadarProfile> findByUserIdIn(Collection<Long> userIds);

    /** Customers who asked for a daily digest. */
    List<RadarProfile> findByDigestTimeIsNotNull();
}
