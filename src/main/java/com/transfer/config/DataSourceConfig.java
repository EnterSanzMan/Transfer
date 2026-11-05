package com.transfer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        if (dbUrl != null && dbUrl.startsWith("jdbc:sqlite:")) {
            String dbPathStr = dbUrl.substring("jdbc:sqlite:".length());
            try {
                Path dbPath = Paths.get(dbPathStr).toAbsolutePath().normalize();
                if (dbPath.getParent() != null) {
                    Files.createDirectories(dbPath.getParent());
                    log.info("Directorio de base de datos SQLite asegurado en: {}", dbPath.getParent());
                }
            } catch (IOException e) {
                log.error("No se pudo crear el directorio para SQLite: {}", dbPathStr, e);
            }
        }
        return properties.initializeDataSourceBuilder().build();
    }
}
