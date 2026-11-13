package io.github.samzhu.bot.execution.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Execution-owned bounded queries; the timeout applies only to these statements. */
@Repository
class TerminalRunScanRepository {
    private final JdbcTemplate jdbc;
    private final int queryTimeoutSeconds;

    TerminalRunScanRepository(JdbcTemplate jdbc,
            @Value("${bot.execution.completion-sweep.query-timeout-seconds:2}") int queryTimeoutSeconds) {
        if (queryTimeoutSeconds < 1) throw new IllegalArgumentException("Completion sweep query timeout must be positive");
        this.jdbc = jdbc;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    Optional<String> highestRunId() {
        requireNoTransaction();
        String id = jdbc.execute((ConnectionCallback<String>) connection -> {
            try (var statement = connection.prepareStatement("select max(id) from chat_run")) {
                statement.setQueryTimeout(queryTimeoutSeconds);
                try (var rows = statement.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
            }
        });
        return Optional.ofNullable(id);
    }

    List<String> terminalRunIds(String after, String upper, int limit) {
        requireNoTransaction();
        return jdbc.execute((ConnectionCallback<List<String>>) connection -> {
            try (var statement = connection.prepareStatement("""
                    select id from chat_run where id > ? and id <= ?
                      and status in ('COMPLETED','FAILED','CANCELLED') order by id limit ?
                    """)) {
                statement.setQueryTimeout(queryTimeoutSeconds);
                statement.setString(1, after);
                statement.setString(2, upper);
                statement.setInt(3, limit);
                var ids = new ArrayList<String>();
                try (var rows = statement.executeQuery()) { while (rows.next()) ids.add(rows.getString(1)); }
                return List.copyOf(ids);
            }
        });
    }

    private void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Completion scan must not hold a Run transaction");
    }
}
