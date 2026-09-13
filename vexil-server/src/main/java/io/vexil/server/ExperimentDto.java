package io.vexil.server;

import io.vexil.core.model.Experiment;
import io.vexil.core.model.Variant;
import io.vexil.core.targeting.AttributeRule;
import io.vexil.core.targeting.TargetingRule;

import java.util.List;
import java.util.Map;

/**
 * Wire representation of an experiment as served to SDKs.
 *
 * <p>Only declarative {@link AttributeRule} targeting survives serialization; programmatic
 * {@link TargetingRule} implementations are inherently in-process and are skipped here.
 */
public record ExperimentDto(
        String key,
        String status,
        String salt,
        double trafficAllocation,
        List<VariantDto> variants,
        List<RuleDto> targetingRules) {

    public record VariantDto(String key, double weight, Map<String, String> payload) {
    }

    public record RuleDto(String attribute, String operator, List<String> values) {
    }

    public static ExperimentDto from(Experiment experiment) {
        List<VariantDto> variants = experiment.variants().stream()
                .map(v -> new VariantDto(v.key(), v.weight(), v.payload()))
                .toList();
        List<RuleDto> rules = experiment.targetingRules().stream()
                .filter(AttributeRule.class::isInstance)
                .map(AttributeRule.class::cast)
                .map(r -> new RuleDto(r.attribute(), r.operator().name(), r.values()))
                .toList();
        return new ExperimentDto(
                experiment.key(),
                experiment.status().name(),
                experiment.salt(),
                experiment.trafficAllocation(),
                variants,
                rules);
    }

    public static List<ExperimentDto> from(List<Experiment> experiments) {
        return experiments.stream().map(ExperimentDto::from).toList();
    }

    public Experiment toExperiment() {
        List<Variant> variantList = variants.stream()
                .map(v -> new Variant(v.key(), v.weight(), v.payload()))
                .toList();
        List<TargetingRule> ruleList = targetingRules == null ? List.of() : targetingRules.stream()
                .<TargetingRule>map(r -> new AttributeRule(
                        r.attribute(), AttributeRule.Operator.valueOf(r.operator()), r.values()))
                .toList();
        return new Experiment(
                key,
                io.vexil.core.model.ExperimentStatus.valueOf(status),
                salt,
                trafficAllocation,
                variantList,
                ruleList);
    }
}
