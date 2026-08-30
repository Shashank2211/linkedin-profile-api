package com.sahil.linkedinapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LinkedInProfileApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinkedInProfileApiApplication.class, args);
    }
}
