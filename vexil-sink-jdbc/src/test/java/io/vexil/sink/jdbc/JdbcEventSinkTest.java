package io.vexil.sink.jdbc;

import io.vexil.core.spi.ExposureEvent;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcEventSinkTest {

    private static JdbcDataSource inMemoryDb(String name) {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    @Test
    void writesBatchesAndBootstrapsTable() throws Exception {
        var dataSource = inMemoryDb("sink_test");
        var sink = new JdbcEventSink(dataSource).ensureTable();

        sink.accept(List.of(
                new ExposureEvent("exp", "control", "user-1", 1000L),
                new ExposureEvent("exp", "treatment", "user-2", 2000L)));
        sink.accept(List.of(new ExposureEvent("exp", "control", "user-3", 3000L)));

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT COUNT(*), MAX(occurred_at_millis) FROM vexil_exposures")) {
            rows.next();
            assertEquals(3, rows.getInt(1));
            assertEquals(3000L, rows.getLong(2));
        }
    }

    @Test
    void rejectsUnsafeTableNames() {
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcEventSink(inMemoryDb("x"), "exposures; DROP TABLE users"));
    }
}
