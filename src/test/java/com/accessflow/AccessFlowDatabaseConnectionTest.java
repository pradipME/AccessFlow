package com.accessflow;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that Spring Boot can actually open a connection to MySQL.
 *
 * The test is skipped automatically when ACCESSFLOW_DB_PASSWORD is not set,
 * so a fresh clone still passes "mvn clean test" with no database. Set the
 * environment variable to activate it.
 *
 * This is the Phase 1 test that proves the DataSource is wired to MySQL at all.
 * Everything else in the suite assumes it, and would fail with a connection
 * error rather than with a useful message.
 *
 * The assertions are AssertJ rather than the {@code assert} keyword on purpose:
 * a bare {@code assert} is a JVM language feature that only runs when assertions
 * are enabled, so it depends on how the test happens to be launched. Every other
 * test in this suite uses AssertJ, and a test that can silently stop asserting
 * is worse than one that is merely verbose.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class AccessFlowDatabaseConnectionTest {

    private static final Logger log = LoggerFactory.getLogger(AccessFlowDatabaseConnectionTest.class);

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Spring Boot opens a working connection to the accessflow MySQL database")
    void connectionIsEstablished() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(5)).isTrue();

            DatabaseMetaData metaData = connection.getMetaData();
            String product = metaData.getDatabaseProductName();
            String productVersion = metaData.getDatabaseProductVersion();
            String driver = metaData.getDriverName();

            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT DATABASE()")) {
                assertThat(resultSet.next()).isTrue();
                String schema = resultSet.getString(1);

                // The URL is deliberately not logged. It is built from environment
                // variables today, so it holds no credential, but a JDBC URL is a
                // conventional place to embed "user:password@" and a connection
                // diagnostic is exactly the kind of output that gets pasted into a
                // ticket. Everything that identifies the server without carrying a
                // credential is logged instead.
                log.info("MySQL connection established: product={} version={} driver={} schema={}",
                        product, productVersion, driver, schema);

                assertThat(product.toLowerCase()).contains("mysql");
                assertThat(schema).isEqualToIgnoringCase("accessflow");
            }
        }
    }
}
