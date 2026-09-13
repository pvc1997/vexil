package io.vexil.core;

import java.util.Map;

/**
 * The unit being assigned and its targeting attributes.
 *
 * @param unitId     the stable identity to bucket on — typically a user id, but any stable unit
 *                   works (account id, session id, device id)
 * @param attributes attributes available to targeting rules, e.g. {@code country}, {@code plan}
 */
public record EvaluationContext(String unitId, Map<String, Object> attributes) {

    public EvaluationContext {
        if (unitId == null || unitId.isBlank()) {
            throw new IllegalArgumentException("unitId must not be blank");
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static EvaluationContext of(String unitId) {
        return new EvaluationContext(unitId, Map.of());
    }
}
