package io.vexil.wire;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The shared, consistently configured JSON mapper for Vexil's wire format. */
public final class VexilJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // Old SDKs must keep working when the server starts sending new fields.
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private VexilJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
