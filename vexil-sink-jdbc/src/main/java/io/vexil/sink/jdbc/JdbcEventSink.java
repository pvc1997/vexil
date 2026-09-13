package io.vexil.sink.jdbc;

import io.vexil.core.spi.EventSink;
import io.vexil.core.spi.ExposureEvent;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Writes exposure events to a JDBC database with one batched insert per event batch.
 *
 * <p>Works against any database with standard SQL types (Postgres, MySQL, ClickHouse's JDBC
 * driver, H2). The engine invokes sinks from a dedicated virtual thread, so the blocking JDBC
 * calls here never touch an evaluation path. A failed batch throws, which the engine's
 * dispatcher catches and drops — exposure delivery is best-effort by design.
 */
public final class JdbcEventSink implements EventSink {

    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final DataSource dataSource;
    private final String insertSql;
    private final String tableName;

    public JdbcEventSink(DataSource dataSource) {
        this(dataSource, "vexil_exposures");
    }

    public JdbcEventSink(DataSource dataSource, String tableName) {
        if (!SAFE_TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException("invalid table name: " + tableName);
        }
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.insertSql = "INSERT INTO " + tableName
                + " (experiment_key, variant_key, unit_id, occurred_at_millis) VALUES (?, ?, ?, ?)";
    }

    /** Creates the exposure table if it does not exist. Call once at startup if you don't manage DDL yourself. */
    public JdbcEventSink ensureTable() {
        String ddl = "CREATE TABLE IF NOT EXISTS " + tableName + " ("
                + "experiment_key VARCHAR(255) NOT NULL, "
                + "variant_key VARCHAR(255) NOT NULL, "
                + "unit_id VARCHAR(255) NOT NULL, "
                + "occurred_at_millis BIGINT NOT NULL)";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to create exposure table " + tableName, e);
        }
        return this;
    }

    @Override
    public void accept(List<ExposureEvent> batch) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(insertSql)) {
            for (ExposureEvent event : batch) {
                statement.setString(1, event.experimentKey());
                statement.setString(2, event.variantKey());
                statement.setString(3, event.unitId());
                statement.setLong(4, event.timestampMillis());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to insert exposure batch into " + tableName, e);
        }
    }
}
