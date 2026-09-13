package io.vexil.core.targeting;

import io.vexil.core.EvaluationContext;

import java.util.List;

/**
 * A targeting rule that compares one context attribute against a set of values.
 *
 * <p>Numeric operators parse both sides as doubles; a non-numeric attribute never matches them.
 */
public record AttributeRule(String attribute, Operator operator, List<String> values) implements TargetingRule {

    public enum Operator {
        EQUALS,
        NOT_EQUALS,
        /** Attribute equals any of the rule's values. */
        IN,
        /** Attribute string-contains the rule's first value. */
        CONTAINS,
        GREATER_THAN,
        LESS_THAN
    }

    public AttributeRule {
        if (attribute == null || attribute.isBlank()) {
            throw new IllegalArgumentException("attribute must not be blank");
        }
        if (operator == null) {
            throw new IllegalArgumentException("operator must not be null");
        }
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        values = List.copyOf(values);
    }

    @Override
    public boolean matches(EvaluationContext context) {
        Object raw = context.attributes().get(attribute);
        if (raw == null) {
            return operator == Operator.NOT_EQUALS;
        }
        String actual = String.valueOf(raw);
        return switch (operator) {
            case EQUALS -> values.getFirst().equals(actual);
            case NOT_EQUALS -> !values.getFirst().equals(actual);
            case IN -> values.contains(actual);
            case CONTAINS -> actual.contains(values.getFirst());
            case GREATER_THAN -> compareNumeric(actual) > 0;
            case LESS_THAN -> compareNumeric(actual) < 0;
        };
    }

    private int compareNumeric(String actual) {
        try {
            return Double.compare(Double.parseDouble(actual), Double.parseDouble(values.getFirst()));
        } catch (NumberFormatException e) {
            return 0; // non-numeric input matches neither GREATER_THAN nor LESS_THAN
        }
    }
}
