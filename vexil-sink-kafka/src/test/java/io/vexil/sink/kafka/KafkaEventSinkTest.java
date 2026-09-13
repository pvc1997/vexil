package io.vexil.sink.kafka;

import io.vexil.core.spi.ExposureEvent;
import io.vexil.wire.VexilJson;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KafkaEventSinkTest {

    @Test
    void publishesEachEventKeyedByUnitId() throws Exception {
        var producer = new MockProducer<String, String>(true, new StringSerializer(), new StringSerializer());
        var sink = new KafkaEventSink(producer, "vexil.exposures");

        sink.accept(List.of(
                new ExposureEvent("exp", "control", "user-1", 1000L),
                new ExposureEvent("exp", "treatment", "user-2", 2000L)));

        var records = producer.history();
        assertEquals(2, records.size());
        assertEquals("vexil.exposures", records.get(0).topic());
        assertEquals("user-1", records.get(0).key());

        var parsed = VexilJson.mapper().readValue(records.get(1).value(), ExposureEvent.class);
        assertEquals(new ExposureEvent("exp", "treatment", "user-2", 2000L), parsed);
    }
}
