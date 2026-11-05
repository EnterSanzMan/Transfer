package com.transfer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TransferApplication {

    public static void main(String[] args) {
        // Las carpetas de la BBDD y de subidas las crean DataSourceConfig y FileStorageService
        SpringApplication.run(TransferApplication.class, args);
    }
}
