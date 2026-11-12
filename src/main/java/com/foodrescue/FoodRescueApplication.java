package com.foodrescue;

import com.foodrescue.exception.InvalidLaunchOptionException;
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
 *   <li>{@code @ConfigurationPropertiesScan} registers our typed settings classes
 *       ({@link com.foodrescue.config.FoodRescueProperties}, ...) that read the
 *       {@code foodrescue.*} keys from application.yml.</li>
 *   <li>{@code @EnableScheduling} activates {@code @Scheduled} jobs, used to release
 *       reservations that were never collected and to save the data files.</li>
 * </ul>
 *
 * <p>How the program starts is chosen with arguments, see {@link LaunchOptions#USAGE}.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FoodRescueApplication {

    public static void main(String[] args) {
        LaunchOptions options;
        try {
            options = LaunchOptions.parse(args);
        } catch (InvalidLaunchOptionException ex) {
            System.err.println("Error: " + ex.getMessage());
            System.err.println(LaunchOptions.USAGE);
            System.exit(2);
            return;
        }
        if (options.helpRequested()) {
            System.out.println(LaunchOptions.USAGE);
            return;
        }
        SpringApplication.run(FoodRescueApplication.class, options.springArguments().toArray(String[]::new));
    }
}
