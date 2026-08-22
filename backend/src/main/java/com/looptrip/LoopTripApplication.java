package com.looptrip;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.Assert;

@SpringBootApplication
@EnableConfigurationProperties(ConstraintProperties.class)
public class LoopTripApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoopTripApplication.class, args);
    }

    @Bean
    ApplicationRunner requireDashScopeApiKey(@Value("${DASHSCOPE_API_KEY}") String apiKey) {
        Assert.hasText(apiKey, "DASHSCOPE_API_KEY environment variable must not be blank");
        return args -> {
        };
    }
}
