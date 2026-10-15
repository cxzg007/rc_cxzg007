package com.rightcapital.notifications;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;

/** Budget handling for this repository's short PostgreSQL transactions. */
final class DatabaseOperations {
    static final Duration RESPONSE_RESERVE = Duration.ofMillis(100);
    private static final Duration TRANSACTION_RESERVE = Duration.ofMillis(200);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final long acquisitionAllowance;

    DatabaseOperations(JdbcTemplate jdbc, PlatformTransactionManager manager, NotificationProperties properties) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        if (!(jdbc.getDataSource() instanceof HikariDataSource pool))
            throw new IllegalArgumentException("Database budgets require the configured Hikari pool");
        acquisitionAllowance = pool.getConnectionTimeout() + pool.getValidationTimeout();
        if (properties.apiTimeout().toMillis() <= acquisitionAllowance + RESPONSE_RESERVE.toMillis() + TRANSACTION_RESERVE.toMillis())
            throw new IllegalArgumentException("API budget must accommodate pool acquisition and transaction/response reserves");
    }

    <T> T execute(Deadline deadline, Function<Session,T> work) {
        // Hikari has no public per-call timeout. Refuse a new borrow when its configured allowance
        // would consume the remaining budget; never mutate shared pool settings per request.
        if (deadline.remainingMillis() <= acquisitionAllowance + TRANSACTION_RESERVE.toMillis())
            throw new QueryTimeoutException("Insufficient budget for database connection acquisition");
        return transaction.execute(tx -> jdbc.execute((ConnectionCallback<T>) connection -> {
            var session = new Session(connection, deadline);
            try {
                session.networkTimeout();
                T result = work.apply(session);
                session.configure(deadline); // COMMIT also receives only the remaining budget.
                return result;
            } finally {
                connection.setNetworkTimeout(Runnable::run, deadline.cleanupMillis());
            }
        }));
    }

    final class Session {
        private final Connection connection;
        private final Deadline deadline;
        private final Deadline statements;
        private Session(Connection connection, Deadline deadline) {
            this.connection = connection; this.deadline = deadline;
            statements = deadline.reserving(TRANSACTION_RESERVE);
        }
        private void networkTimeout() throws SQLException {
            connection.setNetworkTimeout(Runnable::run, deadline.remainingMillis());
        }
        private void configure(Deadline limit) {
            jdbc.execute((ConnectionCallback<Void>) ignored -> {
                networkTimeout();
                // SET LOCAL prevents one request's SQL timeout leaking into the next pool borrower.
                jdbc.queryForObject("SELECT set_config('statement_timeout', ?, true)", String.class,
                    limit.remainingMillis() + "ms");
                limit.remainingMillis();
                networkTimeout();
                return null;
            });
        }
        int update(String sql, Object... args) {
            configure(statements);
            return jdbc.update(sql, args);
        }
        <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
            configure(statements);
            return jdbc.query(sql, mapper, args);
        }
    }
}
