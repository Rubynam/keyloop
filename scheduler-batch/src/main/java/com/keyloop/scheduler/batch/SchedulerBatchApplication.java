package com.keyloop.scheduler.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SchedulerBatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchedulerBatchApplication.class, args);
    }
}
