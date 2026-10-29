package com.foodrescue.repository;

import com.foodrescue.domain.FoodItemPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FoodItemPhotoRepository extends JpaRepository<FoodItemPhoto, Long> {
}
