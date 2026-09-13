package io.vexil.core.spi;

import java.util.List;

/**
 * Receives exposure events ("unit X saw variant Y of experiment Z").
 *
 * <p>Extension point: implement to forward exposures to Kafka, ClickHouse, a warehouse, or logs.
 * Sinks are invoked in batches from a dedicated virtual thread, never from the caller's
 * evaluation path, so blocking I/O in an implementation is fine and expected.
 */
public interface EventSink extends AutoCloseable {

    void accept(List<ExposureEvent> batch);

    @Override
    default void close() {
    }
}
