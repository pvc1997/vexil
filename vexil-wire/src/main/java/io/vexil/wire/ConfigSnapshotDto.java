package io.vexil.wire;

import io.vexil.core.model.ConfigSnapshot;
import io.vexil.core.model.Experiment;
import io.vexil.core.model.ExperimentStatus;
import io.vexil.core.model.Holdout;
import io.vexil.core.model.Variant;
import io.vexil.core.targeting.AttributeRule;
import io.vexil.core.targeting.TargetingRule;

import java.util.List;
import java.util.Map;

/**
 * Wire representation of a {@link ConfigSnapshot} as exchanged between server and SDKs.
 *
 * <p>Only declarative {@link AttributeRule} targeting survives serialization; programmatic
 * {@link TargetingRule} implementations are inherently in-process and are skipped.
 */
public record ConfigSnapshotDto(List<ExperimentDto> experiments, List<HoldoutDto> holdouts) {

    public record ExperimentDto(
            String key,
            String status,
            String salt,
            double trafficAllocation,
            List<VariantDto> variants,
            List<RuleDto> targetingRules,
            String layerKey,
            double layerRangeStart,
            double layerRangeEnd) {

        static ExperimentDto from(Experiment experiment) {
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
                    rules,
                    experiment.layerKey(),
                    experiment.layerRangeStart(),
                    experiment.layerRangeEnd());
        }

        public Experiment toExperiment() {
            List<Variant> variantList = variants.stream()
                    .map(v -> new Variant(v.key(), v.weight(), v.payload()))
                    .toList();
            List<TargetingRule> ruleList = targetingRules == null ? List.of() : targetingRules.stream()
                    .<TargetingRule>map(r -> new AttributeRule(
                            r.attribute(), AttributeRule.Operator.valueOf(r.operator()), r.values()))
                    .toList();
            // Older payloads without layer fields deserialize with start == end == 0; treat that
            // as "no layer" rather than an invalid range.
            double rangeEnd = layerRangeEnd == 0.0 && layerRangeStart == 0.0 ? 1.0 : layerRangeEnd;
            return new Experiment(
                    key,
                    ExperimentStatus.valueOf(status),
                    salt,
                    trafficAllocation,
                    variantList,
                    ruleList,
                    layerKey,
                    layerRangeStart,
                    rangeEnd);
        }
    }

    public record VariantDto(String key, double weight, Map<String, String> payload) {
    }

    public record RuleDto(String attribute, String operator, List<String> values) {
    }

    public record HoldoutDto(String key, double fraction, String salt) {

        static HoldoutDto from(Holdout holdout) {
            return new HoldoutDto(holdout.key(), holdout.fraction(), holdout.salt());
        }

        public Holdout toHoldout() {
            return new Holdout(key, fraction, salt);
        }
    }

    public static ConfigSnapshotDto from(ConfigSnapshot snapshot) {
        return new ConfigSnapshotDto(
                snapshot.experiments().stream().map(ExperimentDto::from).toList(),
                snapshot.holdouts().stream().map(HoldoutDto::from).toList());
    }

    public ConfigSnapshot toSnapshot() {
        return new ConfigSnapshot(
                experiments == null ? List.of() : experiments.stream().map(ExperimentDto::toExperiment).toList(),
                holdouts == null ? List.of() : holdouts.stream().map(HoldoutDto::toHoldout).toList());
    }
}
