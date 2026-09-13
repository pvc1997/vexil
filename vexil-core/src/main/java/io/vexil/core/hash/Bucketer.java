package io.vexil.core.hash;

/**
 * Maps a (salt, unit) pair to a deterministic point in {@code [0.0, 1.0)}.
 *
 * <p>The salt is normally the experiment key (or an explicit per-experiment salt), so that a
 * given user lands at independent points across different experiments while always landing at
 * the same point for the same experiment. Like {@link MurmurHash3}, this mapping is part of the
 * cross-SDK compatibility contract and must never change silently.
 */
public final class Bucketer {

    private static final int SEED = 0;
    private static final double MAX_UNSIGNED_INT = 4294967296.0; // 2^32

    private Bucketer() {
    }

    /** Returns a deterministic value in {@code [0.0, 1.0)} for the given salt and unit id. */
    public static double bucket(String salt, String unitId) {
        int hash = MurmurHash3.hash32(salt + ":" + unitId, SEED);
        long unsigned = Integer.toUnsignedLong(hash);
        return unsigned / MAX_UNSIGNED_INT;
    }
}
