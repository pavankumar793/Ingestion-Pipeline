package com.rag.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class IngestionPipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionPipelineApplication.class, args);
    }
}
