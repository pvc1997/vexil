package io.vexil.core.model;

/**
 * A global holdout group: a slice of units withheld from <em>all</em> experiments so the
 * cumulative impact of experimentation can be measured against a clean baseline.
 *
 * @param key      stable identifier
 * @param fraction fraction of all units held out, in {@code [0.0, 1.0]}
 * @param salt     bucketing salt — defaults to the key; changing it rotates the held-out population
 */
public record Holdout(String key, double fraction, String salt) {

    public Holdout {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("holdout key must not be blank");
        }
        if (fraction < 0.0 || fraction > 1.0) {
            throw new IllegalArgumentException("holdout fraction must be within [0.0, 1.0]");
        }
        if (salt == null || salt.isBlank()) {
            salt = key;
        }
    }

    public static Holdout of(String key, double fraction) {
        return new Holdout(key, fraction, key);
    }
}
