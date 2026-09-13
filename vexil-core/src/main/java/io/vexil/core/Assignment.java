package io.vexil.core;

import java.util.Map;

/**
 * The result of evaluating an experiment for a unit.
 *
 * @param experimentKey the experiment that was evaluated
 * @param variantKey    the assigned variant's key, or {@code null} when not enrolled
 * @param reason        why this result was produced
 * @param payload       the assigned variant's payload, empty when not enrolled
 */
public record Assignment(String experimentKey, String variantKey, Reason reason, Map<String, String> payload) {

    public enum Reason {
        /** Enrolled and assigned to a variant. */
        ASSIGNED,
        /** No experiment with this key exists in the current config. */
        EXPERIMENT_NOT_FOUND,
        /** The experiment exists but is not in RUNNING status. */
        NOT_RUNNING,
        /** The unit's attributes did not satisfy the targeting rules. */
        NOT_TARGETED,
        /** Eligible, but outside the experiment's traffic allocation. */
        NOT_IN_TRAFFIC
    }

    public boolean enrolled() {
        return reason == Reason.ASSIGNED;
    }

    public static Assignment assigned(String experimentKey, String variantKey, Map<String, String> payload) {
        return new Assignment(experimentKey, variantKey, Reason.ASSIGNED, payload);
    }

    public static Assignment excluded(String experimentKey, Reason reason) {
        return new Assignment(experimentKey, null, reason, Map.of());
    }
}
