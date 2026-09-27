package com.accessflow;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies that Spring Boot can actually open a connection to MySQL.
 *
 * The test is skipped automatically when ACCESSFLOW_DB_PASSWORD is not set,
 * so a fresh clone still passes "mvn clean test" with no database. Set the
 * environment variable to activate it.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "ACCESSFLOW_DB_PASSWORD", matches = ".+")
class AccessFlowDatabaseConnectionTest {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Spring Boot opens a working connection to the accessflow MySQL database")
    void connectionIsEstablished() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assert connection.isValid(5);

            DatabaseMetaData metaData = connection.getMetaData();
            String product = metaData.getDatabaseProductName();
            String productVersion = metaData.getDatabaseProductVersion();
            String url = metaData.getURL();
            String driver = metaData.getDriverName();

            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT DATABASE()")) {
                resultSet.next();
                String schema = resultSet.getString(1);

                System.out.println("=====================================");
                System.out.println("  MySQL connection SUCCESSFUL");
                System.out.println("  product    : " + product + " " + productVersion);
                System.out.println("  driver     : " + driver);
                System.out.println("  url        : " + url);
                System.out.println("  schema     : " + schema);
                System.out.println("=====================================");

                assert product.toLowerCase().contains("mysql");
                assert "accessflow".equalsIgnoreCase(schema);
            }
        }
    }
}
