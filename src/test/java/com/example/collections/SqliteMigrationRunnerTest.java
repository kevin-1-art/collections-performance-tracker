package com.example.collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SqliteMigrationRunnerTest {
    @TempDir Path temporaryDirectory;

    @Test void runsVersionedMigrationsOnceAndPreservesLegacyColumns() throws Exception {
        String jdbcUrl="jdbc:sqlite:"+temporaryDirectory.resolve("tracker.db").toString();
        SqliteMigrationRunner.migrate(jdbcUrl);
        SqliteMigrationRunner.migrate(jdbcUrl);

        try(var connection=DriverManager.getConnection(jdbcUrl);
            var statement=connection.createStatement();
            var results=statement.executeQuery("SELECT count(*) FROM sqlite_schema_history")) {
            results.next();
            assertEquals(2,results.getInt(1));
        }
    }

    @Test void upgradesExistingVersionOneDatabaseWithoutReapplyingItsSchema() throws Exception {
        String jdbcUrl="jdbc:sqlite:"+temporaryDirectory.resolve("legacy.db").toString();
        String migration;
        try(var stream=getClass().getResourceAsStream("/db/migration/sqlite/V1__initial_schema.sql")) {
            assertNotNull(stream);
            migration=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
        try(var connection=DriverManager.getConnection(jdbcUrl);
            var statement=connection.createStatement()) {
            for(String sql:migration.split(";")) if(!sql.isBlank()) statement.execute(sql.trim());
            statement.executeUpdate("INSERT INTO accounts(username,display_name,password_hash,role) VALUES('officer','Test Officer','hash','OFFICER')");
            statement.executeUpdate("INSERT INTO collection_entries(client_name,tracking_number,prn,amount,collected_at,created_by) " +
                    "VALUES('Client','G240712-2665','2270005999848',100.50,'2026-11-09 23:30:00',1)");
        }

        assertDoesNotThrow(()->SqliteMigrationRunner.migrate(jdbcUrl));
        try(var connection=DriverManager.getConnection(jdbcUrl);
            var statement=connection.createStatement();
            var columns=statement.executeQuery("PRAGMA table_info(collection_entries)")) {
            boolean hasDate=false;
            boolean hasWholeShillingAmount=false;
            while(columns.next()) {
                hasDate|="collection_date".equals(columns.getString("name"));
                hasWholeShillingAmount|="amount_ugx".equals(columns.getString("name"));
            }
            assertTrue(hasDate);
            assertTrue(hasWholeShillingAmount);
        }
        try(var connection=DriverManager.getConnection(jdbcUrl);
            var statement=connection.createStatement();
            var record=statement.executeQuery("SELECT collection_date,amount_ugx,legacy_amount FROM collection_entries WHERE id=1")) {
            assertTrue(record.next());
            assertEquals("2026-11-10",record.getString("collection_date"));
            assertEquals(101,record.getInt("amount_ugx"));
            assertEquals(100.50,record.getDouble("legacy_amount"),0.001);
        }
    }
}
