package com.foodrescue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Application entry point.
 *
 * <ul>
 *   <li>{@code @SpringBootApplication} turns on component scanning, auto-configuration and
 *       configuration classes for everything under the {@code com.foodrescue} package.</li>
 *   <li>{@code @ConfigurationPropertiesScan} registers our typed settings class
 *       ({@link com.foodrescue.config.FoodRescueProperties}) that reads the {@code foodrescue.*}
 *       keys from application.yml.</li>
 *   <li>{@code @EnableScheduling} activates {@code @Scheduled} jobs, used to release
 *       reservations that were never collected.</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FoodRescueApplication {

    public static void main(String[] args) {
        SpringApplication.run(FoodRescueApplication.class, args);
    }
}
