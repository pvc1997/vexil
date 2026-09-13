package io.vexil.core;

import io.vexil.core.hash.Bucketer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BucketerTest {

    @Test
    void isDeterministic() {
        assertEquals(Bucketer.bucket("checkout-flow", "user-42"), Bucketer.bucket("checkout-flow", "user-42"));
    }

    @Test
    void differentSaltsGiveIndependentPoints() {
        assertNotEquals(Bucketer.bucket("exp-a", "user-42"), Bucketer.bucket("exp-b", "user-42"));
    }

    @Test
    void staysWithinUnitInterval() {
        for (int i = 0; i < 10_000; i++) {
            double b = Bucketer.bucket("range-check", "unit-" + i);
            assertTrue(b >= 0.0 && b < 1.0, "bucket out of range: " + b);
        }
    }

    @Test
    void isRoughlyUniform() {
        int n = 100_000;
        int buckets = 10;
        int[] counts = new int[buckets];
        for (int i = 0; i < n; i++) {
            counts[(int) (Bucketer.bucket("uniformity", "unit-" + i) * buckets)]++;
        }
        int expected = n / buckets;
        for (int count : counts) {
            assertTrue(Math.abs(count - expected) < expected * 0.05,
                    "decile deviates >5% from uniform: " + count + " vs " + expected);
        }
    }
}
