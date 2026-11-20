package com.sanvi.jobscheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JobschedulerApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobschedulerApplication.class, args);
    }
}
