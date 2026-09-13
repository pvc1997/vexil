package io.vexil.core.hash;

import java.nio.charset.StandardCharsets;

/**
 * MurmurHash3 x86 32-bit.
 *
 * <p>This implementation is part of Vexil's <em>compatibility contract</em>: every SDK, in any
 * language, must produce identical hashes for identical input so that a user receives the same
 * variant no matter where an experiment is evaluated. Do not change this algorithm or the way
 * inputs are encoded ({@link StandardCharsets#UTF_8}) without a major version bump and a new,
 * explicitly versioned bucketing scheme.
 */
public final class MurmurHash3 {

    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;

    private MurmurHash3() {
    }

    public static int hash32(String input, int seed) {
        return hash32(input.getBytes(StandardCharsets.UTF_8), seed);
    }

    public static int hash32(byte[] data, int seed) {
        final int nblocks = data.length / 4;
        int h1 = seed;

        for (int i = 0; i < nblocks; i++) {
            int k1 = (data[i * 4] & 0xff)
                    | ((data[i * 4 + 1] & 0xff) << 8)
                    | ((data[i * 4 + 2] & 0xff) << 16)
                    | ((data[i * 4 + 3] & 0xff) << 24);

            k1 *= C1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= C2;

            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;
        final int tailStart = nblocks * 4;
        switch (data.length & 3) {
            case 3:
                k1 ^= (data[tailStart + 2] & 0xff) << 16;
                // fall through
            case 2:
                k1 ^= (data[tailStart + 1] & 0xff) << 8;
                // fall through
            case 1:
                k1 ^= data[tailStart] & 0xff;
                k1 *= C1;
                k1 = Integer.rotateLeft(k1, 15);
                k1 *= C2;
                h1 ^= k1;
                break;
            default:
                break;
        }

        h1 ^= data.length;
        h1 ^= h1 >>> 16;
        h1 *= 0x85ebca6b;
        h1 ^= h1 >>> 13;
        h1 *= 0xc2b2ae35;
        h1 ^= h1 >>> 16;
        return h1;
    }
}
