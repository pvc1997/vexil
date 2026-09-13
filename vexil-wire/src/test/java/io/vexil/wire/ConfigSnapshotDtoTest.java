package io.vexil.wire;

import io.vexil.core.model.ConfigSnapshot;
import io.vexil.core.model.Experiment;
import io.vexil.core.model.ExperimentStatus;
import io.vexil.core.model.Holdout;
import io.vexil.core.model.Variant;
import io.vexil.core.targeting.AttributeRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConfigSnapshotDtoTest {

    @Test
    void roundTripsThroughJson() throws Exception {
        var snapshot = new ConfigSnapshot(
                List.of(new Experiment(
                        "checkout-cta",
                        ExperimentStatus.RUNNING,
                        "custom-salt",
                        0.4,
                        List.of(new Variant("control", 1, Map.of()),
                                new Variant("treatment", 3, Map.of("color", "green"))),
                        List.of(new AttributeRule("country", AttributeRule.Operator.IN, List.of("DE", "FR"))),
                        "checkout",
                        0.0,
                        0.5)),
                List.of(Holdout.of("global", 0.1)));

        String json = VexilJson.mapper().writeValueAsString(ConfigSnapshotDto.from(snapshot));
        ConfigSnapshot parsed = VexilJson.mapper().readValue(json, ConfigSnapshotDto.class).toSnapshot();

        assertEquals(snapshot, parsed);
    }

    @Test
    void toleratesMissingLayerAndHoldoutFields() throws Exception {
        String legacyJson = """
                {"experiments":[{"key":"e","status":"RUNNING","salt":"e","trafficAllocation":1.0,
                  "variants":[{"key":"on","weight":1.0,"payload":{}}],"targetingRules":[]}]}
                """;
        ConfigSnapshot parsed = VexilJson.mapper().readValue(legacyJson, ConfigSnapshotDto.class).toSnapshot();
        var experiment = parsed.experiments().getFirst();
        assertNull(experiment.layerKey());
        assertEquals(1.0, experiment.layerRangeEnd());
        assertEquals(List.of(), parsed.holdouts());
    }
}
