package com.emergencylink;

import com.emergencylink.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableConfigurationProperties(AppProperties.class)
public class EmergencyLinkApplication {
    public static void main(String[] args) {
        SpringApplication.run(EmergencyLinkApplication.class, args);
    }
}
