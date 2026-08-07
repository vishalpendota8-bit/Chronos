package com.chronos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * @ConfigurationPropertiesScan picks up the @ConfigurationProperties records in
 * com.chronos.config without each one needing an @EnableConfigurationProperties registration.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ChronosApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChronosApplication.class, args);
    }
}
