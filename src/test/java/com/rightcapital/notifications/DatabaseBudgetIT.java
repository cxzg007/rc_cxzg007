package com.rightcapital.notifications;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "spring.datasource.url=${TEST_DB_URL:jdbc:postgresql://127.0.0.1:15432/notification_test}",
    "spring.datasource.username=${TEST_DB_USER:postgres}", "spring.datasource.password=${TEST_DB_PASSWORD:}",
    "spring.datasource.hikari.maximum-pool-size=2", "notify.worker-enabled=false", "notify.clients.business=key-a",
    "notify.allowed-origins=http://127.0.0.1:19091", "notify.api-timeout=1500ms"})
class DatabaseBudgetIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired NotificationProperties properties;
    DatabaseOperations operations() { return new DatabaseOperations(jdbc, transactions, properties); }

    @Test void sequentialSqlStatementsShareTheRemainingTime() {
        long start = System.nanoTime();
        assertThrows(DataAccessException.class, () -> operations().execute(Deadline.after(Duration.ofMillis(1500)), sql -> {
            sql.query("SELECT pg_sleep(0.65)", (rs, row) -> 1);
            return sql.query("SELECT pg_sleep(0.85)", (rs, row) -> 1);
        }));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1800);
    }
    @Test void elapsedUpstreamWorkCannotReceiveANewDatabaseBudget() throws Exception {
        var deadline = Deadline.after(Duration.ofMillis(1500));
        Thread.sleep(650);
        assertThrows(QueryTimeoutException.class, () -> operations().execute(deadline, sql -> {
            fail("Insufficient remaining time must prevent new database work");
            return null;
        }));
    }
    @Test void exhaustedPoolReturnsWithinBudgetAndRecovers() throws Exception {
        var held = new ArrayList<Connection>();
        try {
            for (int i = 0; i < 2; i++) held.add(jdbc.getDataSource().getConnection());
            long start = System.nanoTime();
            assertThrows(CannotCreateTransactionException.class, () -> operations().execute(Deadline.after(Duration.ofMillis(1500)), sql -> {
                fail("No connection is available"); return null;
            }));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1200);
        } finally { for (var connection : held) connection.close(); }
        int recovered = operations().execute(Deadline.after(Duration.ofMillis(1500)), sql -> sql.query("SELECT 1", (rs, row) -> rs.getInt(1)).get(0));
        assertEquals(1, recovered);
    }
    @Test void timedOutTransactionDoesNotLeakSettingsToPoolBorrowers() throws Exception {
        assertThrows(DataAccessException.class, () -> operations().execute(Deadline.after(Duration.ofMillis(1500)), sql ->
            sql.query("SELECT pg_sleep(3)", (rs, row) -> 1)));
        var held = new ArrayList<Connection>();
        try {
            for (int i = 0; i < 2; i++) {
                var connection = jdbc.getDataSource().getConnection(); held.add(connection);
                assertEquals(5000, connection.getNetworkTimeout());
                try (var statement = connection.createStatement(); var rs = statement.executeQuery("SHOW statement_timeout")) {
                    assertTrue(rs.next()); assertEquals("0", rs.getString(1));
                }
            }
        } finally { for (var connection : held) connection.close(); }
    }
}
