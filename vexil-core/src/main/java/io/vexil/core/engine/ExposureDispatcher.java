package io.vexil.core.engine;

import io.vexil.core.spi.EventSink;
import io.vexil.core.spi.ExposureEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decouples exposure recording from the evaluation hot path.
 *
 * <p>Evaluation enqueues events with a non-blocking {@code offer}; a dedicated virtual thread
 * drains the queue in batches and fans out to sinks. The queue is bounded — under sustained sink
 * backpressure new events are dropped and counted rather than blocking assignment, because an
 * A/B decision must never stall a request on analytics I/O.
 */
final class ExposureDispatcher implements AutoCloseable {

    private static final int MAX_BATCH = 500;

    private final LinkedBlockingQueue<ExposureEvent> queue;
    private final List<EventSink> sinks;
    private final Thread drainer;
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean closed;

    ExposureDispatcher(List<EventSink> sinks, int capacity) {
        this.sinks = List.copyOf(sinks);
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.drainer = Thread.ofVirtual().name("vexil-exposure-dispatcher").start(this::drainLoop);
    }

    void submit(ExposureEvent event) {
        if (sinks.isEmpty() || closed) {
            return;
        }
        if (!queue.offer(event)) {
            dropped.incrementAndGet();
        }
    }

    long droppedCount() {
        return dropped.get();
    }

    private void drainLoop() {
        List<ExposureEvent> batch = new ArrayList<>(MAX_BATCH);
        while (!closed || !queue.isEmpty()) {
            batch.clear();
            try {
                ExposureEvent first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, MAX_BATCH - 1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            List<ExposureEvent> snapshot = List.copyOf(batch);
            for (EventSink sink : sinks) {
                try {
                    sink.accept(snapshot);
                } catch (RuntimeException e) {
                    // One misbehaving sink must not starve the others.
                }
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            drainer.join(java.time.Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (EventSink sink : sinks) {
            try {
                sink.close();
            } catch (Exception e) {
                // best effort on shutdown
            }
        }
    }
}
