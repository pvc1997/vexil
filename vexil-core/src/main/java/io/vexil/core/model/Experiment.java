package io.vexil.core.model;

import io.vexil.core.targeting.TargetingRule;

import java.util.List;

/**
 * An experiment definition.
 *
 * @param key               stable identifier, unique within a config source
 * @param status            lifecycle state; only {@link ExperimentStatus#RUNNING} experiments assign
 * @param salt              bucketing salt — defaults to the key; changing it reshuffles all users
 * @param trafficAllocation fraction of eligible traffic enrolled, in {@code [0.0, 1.0]}
 * @param variants          the experiment's arms; at least one, total weight &gt; 0
 * @param targetingRules    all rules must match for a unit to be eligible (AND semantics);
 *                          empty means everyone is eligible
 */
public record Experiment(
        String key,
        ExperimentStatus status,
        String salt,
        double trafficAllocation,
        List<Variant> variants,
        List<TargetingRule> targetingRules) {

    public Experiment {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("experiment key must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("experiment status must not be null");
        }
        if (salt == null || salt.isBlank()) {
            salt = key;
        }
        if (trafficAllocation < 0.0 || trafficAllocation > 1.0) {
            throw new IllegalArgumentException("trafficAllocation must be within [0.0, 1.0]");
        }
        if (variants == null || variants.isEmpty()) {
            throw new IllegalArgumentException("experiment must have at least one variant");
        }
        double totalWeight = variants.stream().mapToDouble(Variant::weight).sum();
        if (totalWeight <= 0) {
            throw new IllegalArgumentException("total variant weight must be > 0");
        }
        variants = List.copyOf(variants);
        targetingRules = targetingRules == null ? List.of() : List.copyOf(targetingRules);
    }

    /** A running experiment over 100% of traffic with the given variants and no targeting. */
    public static Experiment running(String key, Variant... variants) {
        return new Experiment(key, ExperimentStatus.RUNNING, key, 1.0, List.of(variants), List.of());
    }
}
