package io.vexil.core.model;

import java.util.Map;

/**
 * One arm of an experiment.
 *
 * @param key     stable identifier, e.g. {@code "control"} or {@code "treatment"}
 * @param weight  relative traffic weight; weights across an experiment's variants are normalized,
 *                so {@code 1,1} and {@code 50,50} are equivalent
 * @param payload arbitrary configuration delivered to callers assigned to this variant
 */
public record Variant(String key, double weight, Map<String, String> payload) {

    public Variant {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("variant key must not be blank");
        }
        if (weight < 0 || !Double.isFinite(weight)) {
            throw new IllegalArgumentException("variant weight must be a finite non-negative number");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static Variant of(String key, double weight) {
        return new Variant(key, weight, Map.of());
    }
}
