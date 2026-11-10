package com.example.collections;

import java.net.URI;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CollectionsApplication {
    public static void main(String[] args) {
        String databaseUrl=System.getProperty("DB_URL",System.getenv("DB_URL"));
        if(databaseUrl==null||databaseUrl.isBlank()) {
            String renderDatabaseUrl=System.getenv("DATABASE_URL");
            if(renderDatabaseUrl!=null&&!renderDatabaseUrl.isBlank()) {
                databaseUrl=renderJdbcUrl(renderDatabaseUrl);
                System.setProperty("DB_URL",databaseUrl);
            }
        }
        String importServiceUrl=System.getProperty("IMPORT_SERVICE_URL",System.getenv("IMPORT_SERVICE_URL"));
        String importServiceHostPort=System.getenv("IMPORT_SERVICE_HOSTPORT");
        if((importServiceUrl==null||importServiceUrl.isBlank())
                &&importServiceHostPort!=null&&!importServiceHostPort.isBlank()) {
            System.setProperty("IMPORT_SERVICE_URL","http://"+importServiceHostPort);
        }
        if(databaseUrl!=null&&databaseUrl.startsWith("jdbc:sqlite:")) {
            SqliteMigrationRunner.migrate(databaseUrl);
            System.setProperty("spring.flyway.enabled","false");
        }
        SpringApplication.run(CollectionsApplication.class, args);
    }

    static String renderJdbcUrl(String databaseUrl) {
        URI uri=URI.create(databaseUrl);
        String scheme=uri.getScheme();
        if((!"postgresql".equalsIgnoreCase(scheme)&&!"postgres".equalsIgnoreCase(scheme))
                ||uri.getHost()==null||uri.getRawPath()==null||uri.getRawPath().length()<2) {
            throw new IllegalArgumentException("DATABASE_URL must be a PostgreSQL connection URL");
        }
        String query=uri.getRawQuery();
        if(query==null||query.isBlank()) query="sslmode=require";
        else if(!query.matches("(?i).*\\bsslmode=.*")) query+="&sslmode=require";
        int port=uri.getPort()<0?5432:uri.getPort();
        return "jdbc:postgresql://"+uri.getHost()+":"+port+uri.getRawPath()+"?"+query;
    }
}
