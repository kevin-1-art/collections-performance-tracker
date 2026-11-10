package com.example.collections;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CollectionsApplication {
    public static void main(String[] args) {
        String databaseUrl=System.getProperty("DB_URL",System.getenv("DB_URL"));
        if(databaseUrl!=null&&databaseUrl.startsWith("jdbc:sqlite:")) {
            SqliteMigrationRunner.migrate(databaseUrl);
            System.setProperty("spring.flyway.enabled","false");
        }
        SpringApplication.run(CollectionsApplication.class, args);
    }
}
