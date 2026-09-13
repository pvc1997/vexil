package io.vexil.core.model;

import java.util.List;

/**
 * The complete experimentation configuration at a point in time. Config sources always deliver
 * full snapshots, never deltas, so a consumer can atomically swap its state.
 */
public record ConfigSnapshot(List<Experiment> experiments, List<Holdout> holdouts) {

    public ConfigSnapshot {
        experiments = experiments == null ? List.of() : List.copyOf(experiments);
        holdouts = holdouts == null ? List.of() : List.copyOf(holdouts);
    }

    public static ConfigSnapshot of(List<Experiment> experiments) {
        return new ConfigSnapshot(experiments, List.of());
    }

    public static ConfigSnapshot of(Experiment... experiments) {
        return new ConfigSnapshot(List.of(experiments), List.of());
    }
}
