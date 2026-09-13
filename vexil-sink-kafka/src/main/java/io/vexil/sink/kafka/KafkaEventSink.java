package io.vexil.sink.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.vexil.core.spi.EventSink;
import io.vexil.core.spi.ExposureEvent;
import io.vexil.wire.VexilJson;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.List;
import java.util.Map;

/**
 * Publishes exposure events to a Kafka topic as JSON, keyed by unit id so that all exposures of
 * one unit land in the same partition (preserving per-unit ordering for downstream analysis).
 *
 * <p>The engine invokes sinks from a dedicated virtual thread, so the blocking flush here never
 * touches an evaluation path.
 */
public final class KafkaEventSink implements EventSink {

    private final Producer<String, String> producer;
    private final String topic;
    private final boolean ownsProducer;

    /** Creates a sink with its own producer connected to the given bootstrap servers. */
    public KafkaEventSink(String bootstrapServers, String topic) {
        this(new KafkaProducer<>(
                        Map.of("bootstrap.servers", bootstrapServers,
                                "acks", "1",
                                "linger.ms", 20),
                        new StringSerializer(), new StringSerializer()),
                topic, true);
    }

    /** Creates a sink over an externally managed producer (which the caller closes). */
    public KafkaEventSink(Producer<String, String> producer, String topic) {
        this(producer, topic, false);
    }

    private KafkaEventSink(Producer<String, String> producer, String topic, boolean ownsProducer) {
        this.producer = producer;
        this.topic = topic;
        this.ownsProducer = ownsProducer;
    }

    @Override
    public void accept(List<ExposureEvent> batch) {
        for (ExposureEvent event : batch) {
            try {
                String json = VexilJson.mapper().writeValueAsString(event);
                producer.send(new ProducerRecord<>(topic, event.unitId(), json));
            } catch (JsonProcessingException e) {
                // an unserializable event is dropped; the rest of the batch still ships
            }
        }
        producer.flush();
    }

    @Override
    public void close() {
        if (ownsProducer) {
            producer.close();
        }
    }
}
