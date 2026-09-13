package io.vexil.core.targeting;

import io.vexil.core.EvaluationContext;

/**
 * Decides whether a unit is eligible for an experiment.
 *
 * <p>This is an extension point: implement it for custom eligibility logic (percentage ramps by
 * region, entitlement checks, ML-scored segments). {@link AttributeRule} covers the common
 * attribute-comparison cases.
 */
@FunctionalInterface
public interface TargetingRule {

    boolean matches(EvaluationContext context);
}
