package io.vexil.core.spi;

/** A record of a unit being assigned to a variant at a point in time. */
public record ExposureEvent(String experimentKey, String variantKey, String unitId, long timestampMillis) {
}
