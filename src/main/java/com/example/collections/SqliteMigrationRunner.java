package com.example.collections;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

final class SqliteMigrationRunner {
    private static final List<Migration> MIGRATIONS=List.of(
            new Migration(1,"initial_schema"),
            new Migration(2,"use_collection_calendar_dates"));

    private SqliteMigrationRunner() {}

    static void migrate(String jdbcUrl) {
        try(Connection connection=DriverManager.getConnection(jdbcUrl)) {
            try(Statement statement=connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS sqlite_schema_history (
                          version INTEGER PRIMARY KEY,
                          description VARCHAR(200) NOT NULL,
                          installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )
                        """);
            }
            adoptExistingSchema(connection);
            for(Migration migration:MIGRATIONS) {
                if(applied(connection,migration.version())) continue;
                apply(connection,migration);
            }
        } catch(SQLException|IOException ex) {
            throw new IllegalStateException("Could not migrate the SQLite database",ex);
        }
    }

    private static void adoptExistingSchema(Connection connection) throws SQLException {
        if(applied(connection,1)||!tableExists(connection,"accounts")) return;
        markApplied(connection,MIGRATIONS.get(0));
        if(tableExists(connection,"collection_entries")
                &&columnExists(connection,"collection_entries","collection_date")
                &&columnExists(connection,"collection_entries","amount_ugx")) {
            markApplied(connection,MIGRATIONS.get(1));
        }
    }

    private static boolean tableExists(Connection connection,String table) throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1,table);
            try(ResultSet results=statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static boolean columnExists(Connection connection,String table,String column) throws SQLException {
        if(!Set.of("accounts","collection_entries").contains(table)) throw new IllegalArgumentException("Unexpected table");
        try(Statement statement=connection.createStatement();
            ResultSet columns=statement.executeQuery("PRAGMA table_info("+table+")")) {
            while(columns.next()) if(column.equals(columns.getString("name"))) return true;
            return false;
        }
    }

    private static boolean applied(Connection connection,int version) throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement(
                "SELECT 1 FROM sqlite_schema_history WHERE version=?")) {
            statement.setInt(1,version);
            try(ResultSet results=statement.executeQuery()) {
                return results.next();
            }
        }
    }

    private static void markApplied(Connection connection,Migration migration) throws SQLException {
        try(PreparedStatement history=connection.prepareStatement(
                "INSERT INTO sqlite_schema_history(version,description) VALUES(?,?)")) {
            history.setInt(1,migration.version());
            history.setString(2,migration.description());
            history.executeUpdate();
        }
    }

    private static void apply(Connection connection,Migration migration) throws SQLException,IOException {
        String resource="/db/migration/sqlite/V"+migration.version()+"__"+migration.description()+".sql";
        String script;
        try(var stream=SqliteMigrationRunner.class.getResourceAsStream(resource)) {
            if(stream==null) throw new IOException("Missing SQLite migration "+resource);
            script=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
        connection.setAutoCommit(false);
        try(Statement statement=connection.createStatement()) {
            for(String sql:script.split(";")) {
                if(!sql.isBlank()) statement.execute(sql.trim());
            }
            markApplied(connection,migration);
            connection.commit();
        } catch(SQLException|RuntimeException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private record Migration(int version,String description) {}
}
