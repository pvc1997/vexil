package io.vexil.core.model;

import io.vexil.core.targeting.TargetingRule;

import java.util.List;

/**
 * An experiment definition.
 *
 * <p>Experiments that share a {@code layerKey} draw from a single per-layer bucket space and are
 * enrolled only when the unit's layer point falls inside their {@code [layerRangeStart,
 * layerRangeEnd)} slice — giving experiments with disjoint slices mutual exclusion. Slice
 * disjointness is a config-authoring concern; the engine does not validate it across experiments.
 *
 * @param key               stable identifier, unique within a config source
 * @param status            lifecycle state; only {@link ExperimentStatus#RUNNING} experiments assign
 * @param salt              bucketing salt — defaults to the key; changing it reshuffles all users
 * @param trafficAllocation fraction of eligible traffic enrolled, in {@code [0.0, 1.0]}
 * @param variants          the experiment's arms; at least one, total weight &gt; 0
 * @param targetingRules    all rules must match for a unit to be eligible (AND semantics);
 *                          empty means everyone is eligible
 * @param layerKey          mutual-exclusion layer, or {@code null} for none
 * @param layerRangeStart   inclusive start of this experiment's slice of the layer, in [0,1]
 * @param layerRangeEnd     exclusive end of this experiment's slice of the layer, in [0,1]
 */
public record Experiment(
        String key,
        ExperimentStatus status,
        String salt,
        double trafficAllocation,
        List<Variant> variants,
        List<TargetingRule> targetingRules,
        String layerKey,
        double layerRangeStart,
        double layerRangeEnd) {

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
        if (layerKey != null && layerKey.isBlank()) {
            layerKey = null;
        }
        if (layerRangeStart < 0.0 || layerRangeEnd > 1.0 || layerRangeStart >= layerRangeEnd) {
            throw new IllegalArgumentException("layer range must satisfy 0 <= start < end <= 1");
        }
        variants = List.copyOf(variants);
        targetingRules = targetingRules == null ? List.of() : List.copyOf(targetingRules);
    }

    /** An experiment outside any layer (full [0,1) slice). */
    public Experiment(String key, ExperimentStatus status, String salt, double trafficAllocation,
                      List<Variant> variants, List<TargetingRule> targetingRules) {
        this(key, status, salt, trafficAllocation, variants, targetingRules, null, 0.0, 1.0);
    }

    /** A running experiment over 100% of traffic with the given variants and no targeting. */
    public static Experiment running(String key, Variant... variants) {
        return new Experiment(key, ExperimentStatus.RUNNING, key, 1.0, List.of(variants), List.of());
    }

    /** Copy of this experiment placed in the given layer slice. */
    public Experiment inLayer(String layerKey, double rangeStart, double rangeEnd) {
        return new Experiment(key, status, salt, trafficAllocation, variants, targetingRules,
                layerKey, rangeStart, rangeEnd);
    }
}
