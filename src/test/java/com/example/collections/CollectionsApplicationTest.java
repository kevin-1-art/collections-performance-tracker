package com.example.collections;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CollectionsApplicationTest {
    @Test void convertsRenderPostgresUrlToJdbcWithoutIncludingCredentials() {
        assertEquals(
                "jdbc:postgresql://db.internal:5432/collections?sslmode=require",
                CollectionsApplication.renderJdbcUrl("postgresql://dbuser:secret@db.internal:5432/collections"));
    }

    @Test void preservesDatabaseQueryOptionsAndExistingSslMode() {
        assertEquals(
                "jdbc:postgresql://db.internal:5432/collections?sslmode=verify-full&connectTimeout=5",
                CollectionsApplication.renderJdbcUrl(
                        "postgres://dbuser:secret@db.internal:5432/collections?sslmode=verify-full&connectTimeout=5"));
    }

    @Test void defaultsToPostgresPortWhenTheConnectionUrlOmitsIt() {
        assertEquals(
                "jdbc:postgresql://db.internal:5432/collections?sslmode=require",
                CollectionsApplication.renderJdbcUrl("postgresql://dbuser:secret@db.internal/collections"));
    }

    @Test void rejectsNonPostgresDatabaseUrls() {
        assertThrows(IllegalArgumentException.class,
                () -> CollectionsApplication.renderJdbcUrl("mysql://db.internal/collections"));
    }
}
