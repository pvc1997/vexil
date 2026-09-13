package io.vexil.core.engine;

import io.vexil.core.Assignment;
import io.vexil.core.EvaluationContext;
import io.vexil.core.hash.Bucketer;
import io.vexil.core.model.ConfigSnapshot;
import io.vexil.core.model.Experiment;
import io.vexil.core.model.ExperimentStatus;
import io.vexil.core.model.Holdout;
import io.vexil.core.model.Variant;
import io.vexil.core.spi.ConfigSource;
import io.vexil.core.spi.EventSink;
import io.vexil.core.spi.ExposureEvent;
import io.vexil.core.targeting.TargetingRule;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The assignment engine: evaluates experiments for units, deterministically and without I/O.
 *
 * <p>Evaluation is pure CPU (a few Murmur3 hashes and a table walk) and safe to call on any hot
 * path. Exposure events are handed to sinks asynchronously on a virtual thread; config updates
 * from a watching {@link ConfigSource} are applied atomically via a volatile snapshot swap.
 *
 * <p>Evaluation order: experiment exists → running → not in a global holdout → targeting rules
 * match → inside the experiment's layer slice → inside traffic allocation → variant pick.
 */
public final class ExperimentEngine implements AutoCloseable {

    private static final int DEFAULT_QUEUE_CAPACITY = 100_000;

    private final ConfigSource configSource;
    private final ExposureDispatcher dispatcher;
    private final Clock clock;
    private volatile State state;

    private record State(Map<String, Experiment> experiments, List<Holdout> holdouts) {
        static State from(ConfigSnapshot snapshot) {
            Map<String, Experiment> byKey = new HashMap<>();
            for (Experiment experiment : snapshot.experiments()) {
                byKey.put(experiment.key(), experiment);
            }
            return new State(Map.copyOf(byKey), snapshot.holdouts());
        }
    }

    public ExperimentEngine(ConfigSource configSource, List<EventSink> sinks) {
        this(configSource, sinks, Clock.systemUTC());
    }

    public ExperimentEngine(ConfigSource configSource, List<EventSink> sinks, Clock clock) {
        this.configSource = Objects.requireNonNull(configSource, "configSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.dispatcher = new ExposureDispatcher(sinks, DEFAULT_QUEUE_CAPACITY);
        this.state = State.from(configSource.load());
        configSource.watch(updated -> this.state = State.from(updated));
    }

    /**
     * Evaluates the experiment for the given context and, when the unit is enrolled, records an
     * exposure event asynchronously.
     */
    public Assignment evaluate(String experimentKey, EvaluationContext context) {
        Assignment assignment = evaluateSilently(experimentKey, context);
        if (assignment.enrolled()) {
            dispatcher.submit(new ExposureEvent(
                    experimentKey, assignment.variantKey(), context.unitId(), clock.millis()));
        }
        return assignment;
    }

    /** Evaluates without recording an exposure — for lookups that don't imply the user saw anything. */
    public Assignment evaluateSilently(String experimentKey, EvaluationContext context) {
        State current = state;
        Experiment experiment = current.experiments().get(experimentKey);
        if (experiment == null) {
            return Assignment.excluded(experimentKey, Assignment.Reason.EXPERIMENT_NOT_FOUND);
        }
        if (experiment.status() != ExperimentStatus.RUNNING) {
            return Assignment.excluded(experimentKey, Assignment.Reason.NOT_RUNNING);
        }
        for (Holdout holdout : current.holdouts()) {
            if (Bucketer.bucket(holdout.salt() + ":holdout", context.unitId()) < holdout.fraction()) {
                return Assignment.excluded(experimentKey, Assignment.Reason.IN_HOLDOUT);
            }
        }
        for (TargetingRule rule : experiment.targetingRules()) {
            if (!rule.matches(context)) {
                return Assignment.excluded(experimentKey, Assignment.Reason.NOT_TARGETED);
            }
        }

        if (experiment.layerKey() != null) {
            double layerPoint = Bucketer.bucket("layer:" + experiment.layerKey(), context.unitId());
            if (layerPoint < experiment.layerRangeStart() || layerPoint >= experiment.layerRangeEnd()) {
                return Assignment.excluded(experimentKey, Assignment.Reason.NOT_IN_LAYER);
            }
        }

        // Two independent hash points: one decides enrollment, one picks the variant. Using the
        // same point for both would correlate "who is enrolled" with "which variant", biasing
        // ramp-ups (users enrolled at 10% traffic would all sit at the low end of variant space).
        double trafficPoint = Bucketer.bucket(experiment.salt() + ":traffic", context.unitId());
        if (trafficPoint >= experiment.trafficAllocation()) {
            return Assignment.excluded(experimentKey, Assignment.Reason.NOT_IN_TRAFFIC);
        }

        double variantPoint = Bucketer.bucket(experiment.salt(), context.unitId());
        Variant variant = pickVariant(experiment.variants(), variantPoint);
        return Assignment.assigned(experimentKey, variant.key(), variant.payload());
    }

    /** Number of exposure events dropped because sinks could not keep up. */
    public long droppedExposures() {
        return dispatcher.droppedCount();
    }

    private static Variant pickVariant(List<Variant> variants, double point) {
        double totalWeight = variants.stream().mapToDouble(Variant::weight).sum();
        double cumulative = 0;
        for (Variant variant : variants) {
            cumulative += variant.weight() / totalWeight;
            if (point < cumulative) {
                return variant;
            }
        }
        return variants.getLast(); // guards floating-point edge where cumulative sums to <1.0
    }

    @Override
    public void close() {
        dispatcher.close();
        try {
            configSource.close();
        } catch (Exception e) {
            // best effort on shutdown
        }
    }
}
