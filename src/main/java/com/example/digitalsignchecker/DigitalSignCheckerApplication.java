package com.example.digitalsignchecker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class DigitalSignCheckerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DigitalSignCheckerApplication.class, args);
    }

}
