package io.vexil.core;

import io.vexil.core.engine.ExperimentEngine;
import io.vexil.core.model.ConfigSnapshot;
import io.vexil.core.model.Experiment;
import io.vexil.core.model.Holdout;
import io.vexil.core.model.Variant;
import io.vexil.core.source.InMemoryConfigSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayersAndHoldoutsTest {

    @Test
    void experimentsInDisjointLayerSlicesAreMutuallyExclusive() {
        var expA = Experiment.running("exp-a", Variant.of("on", 1)).inLayer("checkout", 0.0, 0.5);
        var expB = Experiment.running("exp-b", Variant.of("on", 1)).inLayer("checkout", 0.5, 1.0);
        try (var engine = new ExperimentEngine(new InMemoryConfigSource(List.of(expA, expB)), List.of())) {
            int n = 20_000;
            int inA = 0;
            int inB = 0;
            for (int i = 0; i < n; i++) {
                var ctx = EvaluationContext.of("user-" + i);
                boolean a = engine.evaluateSilently("exp-a", ctx).enrolled();
                boolean b = engine.evaluateSilently("exp-b", ctx).enrolled();
                assertFalse(a && b, "unit enrolled in two experiments sharing a layer");
                if (a) inA++;
                if (b) inB++;
            }
            assertEquals(n, inA + inB, "disjoint slices covering [0,1) must partition all units");
            assertTrue(Math.abs(inA - n / 2.0) < n * 0.01, "expected ~50/50 layer split, got " + inA);
        }
    }

    @Test
    void unitOutsideLayerSliceGetsLayerReason() {
        var experiment = Experiment.running("solo", Variant.of("on", 1)).inLayer("layer-x", 0.0, 0.5);
        try (var engine = new ExperimentEngine(new InMemoryConfigSource(List.of(experiment)), List.of())) {
            boolean sawLayerExclusion = false;
            for (int i = 0; i < 100 && !sawLayerExclusion; i++) {
                var assignment = engine.evaluateSilently("solo", EvaluationContext.of("user-" + i));
                if (!assignment.enrolled()) {
                    assertEquals(Assignment.Reason.NOT_IN_LAYER, assignment.reason());
                    sawLayerExclusion = true;
                }
            }
            assertTrue(sawLayerExclusion, "expected at least one unit outside a 50% slice");
        }
    }

    @Test
    void holdoutWithholdsUnitsFromAllExperiments() {
        var snapshot = new ConfigSnapshot(
                List.of(Experiment.running("exp-a", Variant.of("on", 1)),
                        Experiment.running("exp-b", Variant.of("on", 1))),
                List.of(Holdout.of("global-holdout", 0.2)));
        try (var engine = new ExperimentEngine(new InMemoryConfigSource(snapshot), List.of())) {
            int n = 50_000;
            int heldOut = 0;
            for (int i = 0; i < n; i++) {
                var ctx = EvaluationContext.of("user-" + i);
                var a = engine.evaluateSilently("exp-a", ctx);
                var b = engine.evaluateSilently("exp-b", ctx);
                if (a.reason() == Assignment.Reason.IN_HOLDOUT) {
                    assertEquals(Assignment.Reason.IN_HOLDOUT, b.reason(),
                            "holdout must apply to every experiment consistently");
                    heldOut++;
                }
            }
            double share = heldOut / (double) n;
            assertTrue(Math.abs(share - 0.2) < 0.01, "expected ~20% held out, got " + share);
        }
    }

    @Test
    void holdoutMembershipIsDeterministic() {
        var snapshot = new ConfigSnapshot(
                List.of(Experiment.running("exp", Variant.of("on", 1))),
                List.of(Holdout.of("h", 0.5)));
        try (var engine = new ExperimentEngine(new InMemoryConfigSource(snapshot), List.of())) {
            var first = engine.evaluateSilently("exp", EvaluationContext.of("user-7")).reason();
            for (int i = 0; i < 100; i++) {
                assertEquals(first, engine.evaluateSilently("exp", EvaluationContext.of("user-7")).reason());
            }
        }
    }
}
