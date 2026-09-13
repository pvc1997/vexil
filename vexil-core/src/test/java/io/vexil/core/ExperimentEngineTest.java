package io.vexil.core;

import io.vexil.core.engine.ExperimentEngine;
import io.vexil.core.model.Experiment;
import io.vexil.core.model.ExperimentStatus;
import io.vexil.core.model.Variant;
import io.vexil.core.source.InMemoryConfigSource;
import io.vexil.core.spi.EventSink;
import io.vexil.core.spi.ExposureEvent;
import io.vexil.core.targeting.AttributeRule;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentEngineTest {

    private static ExperimentEngine engine(Experiment... experiments) {
        return new ExperimentEngine(new InMemoryConfigSource(List.of(experiments)), List.of());
    }

    @Test
    void assignsDeterministically() {
        try (var engine = engine(Experiment.running("exp", Variant.of("control", 1), Variant.of("treatment", 1)))) {
            Assignment first = engine.evaluate("exp", EvaluationContext.of("user-1"));
            for (int i = 0; i < 100; i++) {
                assertEquals(first.variantKey(), engine.evaluate("exp", EvaluationContext.of("user-1")).variantKey());
            }
        }
    }

    @Test
    void respectsVariantWeights() {
        var experiment = Experiment.running("weighted", Variant.of("a", 80), Variant.of("b", 20));
        try (var engine = engine(experiment)) {
            Map<String, Integer> counts = new HashMap<>();
            int n = 50_000;
            for (int i = 0; i < n; i++) {
                var assignment = engine.evaluate("weighted", EvaluationContext.of("user-" + i));
                counts.merge(assignment.variantKey(), 1, Integer::sum);
            }
            double aShare = counts.get("a") / (double) n;
            assertTrue(Math.abs(aShare - 0.8) < 0.01, "expected ~80% in variant a, got " + aShare);
        }
    }

    @Test
    void respectsTrafficAllocation() {
        var experiment = new Experiment("ramp", ExperimentStatus.RUNNING, null, 0.25,
                List.of(Variant.of("control", 1), Variant.of("treatment", 1)), List.of());
        try (var engine = engine(experiment)) {
            int n = 50_000;
            int enrolled = 0;
            for (int i = 0; i < n; i++) {
                if (engine.evaluate("ramp", EvaluationContext.of("user-" + i)).enrolled()) {
                    enrolled++;
                }
            }
            double share = enrolled / (double) n;
            assertTrue(Math.abs(share - 0.25) < 0.01, "expected ~25% enrolled, got " + share);
        }
    }

    @Test
    void enrollmentIsStableAsTrafficRampsUp() {
        var variants = List.of(Variant.of("control", 1), Variant.of("treatment", 1));
        var at25 = new Experiment("ramp", ExperimentStatus.RUNNING, null, 0.25, variants, List.<io.vexil.core.targeting.TargetingRule>of());
        var at50 = new Experiment("ramp", ExperimentStatus.RUNNING, null, 0.50, variants, List.<io.vexil.core.targeting.TargetingRule>of());
        try (var early = engine(at25); var later = engine(at50)) {
            for (int i = 0; i < 10_000; i++) {
                var ctx = EvaluationContext.of("user-" + i);
                var before = early.evaluate("ramp", ctx);
                if (before.enrolled()) {
                    var after = later.evaluate("ramp", ctx);
                    assertTrue(after.enrolled(), "user fell out of experiment when traffic increased");
                    assertEquals(before.variantKey(), after.variantKey(), "variant changed during ramp-up");
                }
            }
        }
    }

    @Test
    void appliesTargetingRules() {
        var experiment = new Experiment("targeted", ExperimentStatus.RUNNING, null, 1.0,
                List.of(Variant.of("on", 1)),
                List.of(new AttributeRule("country", AttributeRule.Operator.IN, List.of("DE", "FR"))));
        try (var engine = engine(experiment)) {
            var inTarget = engine.evaluate("targeted", new EvaluationContext("u1", Map.of("country", "DE")));
            var outOfTarget = engine.evaluate("targeted", new EvaluationContext("u2", Map.of("country", "US")));
            assertTrue(inTarget.enrolled());
            assertEquals(Assignment.Reason.NOT_TARGETED, outOfTarget.reason());
        }
    }

    @Test
    void doesNotAssignWhenNotRunning() {
        var experiment = new Experiment("paused", ExperimentStatus.PAUSED, null, 1.0,
                List.of(Variant.of("on", 1)), List.of());
        try (var engine = engine(experiment)) {
            assertEquals(Assignment.Reason.NOT_RUNNING, engine.evaluate("paused", EvaluationContext.of("u1")).reason());
            assertEquals(Assignment.Reason.EXPERIMENT_NOT_FOUND,
                    engine.evaluate("missing", EvaluationContext.of("u1")).reason());
        }
    }

    @Test
    void reactsToConfigUpdates() {
        var source = new InMemoryConfigSource(List.of(Experiment.running("live", Variant.of("on", 1))));
        try (var engine = new ExperimentEngine(source, List.of())) {
            assertTrue(engine.evaluate("live", EvaluationContext.of("u1")).enrolled());
            source.update(List.of(new Experiment("live", ExperimentStatus.PAUSED, null, 1.0,
                    List.of(Variant.of("on", 1)), List.of())));
            assertFalse(engine.evaluate("live", EvaluationContext.of("u1")).enrolled());
        }
    }

    @Test
    void deliversExposuresToSinks() throws Exception {
        var received = new ConcurrentLinkedQueue<ExposureEvent>();
        EventSink sink = received::addAll;
        var source = new InMemoryConfigSource(List.of(Experiment.running("exp", Variant.of("on", 1))));
        try (var engine = new ExperimentEngine(source, List.of(sink))) {
            engine.evaluate("exp", EvaluationContext.of("user-1"));
            engine.evaluateSilently("exp", EvaluationContext.of("user-2"));
            long deadline = System.currentTimeMillis() + 5_000;
            while (received.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
        }
        assertEquals(1, received.size(), "only the non-silent evaluation should emit an exposure");
        var event = received.peek();
        assertEquals("exp", event.experimentKey());
        assertEquals("user-1", event.unitId());
    }
}
