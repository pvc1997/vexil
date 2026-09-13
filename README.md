# Vexil

A modern, extensible A/B testing and experimentation framework for the JVM, built on Java 21+ virtual threads.

Vexil is layered so each piece is useful on its own:

| Module | What it is |
|---|---|
| `vexil-core` | Zero-dependency assignment engine: deterministic Murmur3 bucketing, variant weights, traffic ramps, targeting rules, async exposure tracking. Embed it in any JVM app. |
| `vexil-server` | Self-hostable config delivery (Server-Sent Events, one virtual thread per connection) and exposure-event ingestion. |

## Quick start (embedded)

```java
var source = new InMemoryConfigSource(List.of(
    Experiment.running("checkout-cta",
        Variant.of("control", 1),
        Variant.of("treatment", 1))));

try (var engine = new ExperimentEngine(source, List.of(myKafkaSink))) {
    Assignment a = engine.evaluate("checkout-cta", EvaluationContext.of(userId));
    if (a.enrolled() && a.variantKey().equals("treatment")) {
        // render the new call-to-action
    }
}
```

Evaluation is pure CPU — a hash and a table walk — and safe on any hot path. Exposure events
are batched and delivered to your `EventSink`s from a dedicated virtual thread, never blocking
the request.

## Quick start (server)

```bash
mvn package
java -cp vexil-server/target/classes:vexil-core/target/classes:<jackson-jars> io.vexil.server.Main 8080

curl localhost:8080/api/experiments        # current config
curl -N localhost:8080/api/stream          # live SSE config stream
```

## Design principles

- **Determinism is a contract.** The same unit gets the same variant everywhere, forever:
  Murmur3-32 over `salt:unitId`, UTF-8 encoded. This algorithm is versioned and never changes
  silently — it is what makes cross-language SDKs possible.
- **Ramp-safe enrollment.** Traffic allocation and variant choice use independent hash points,
  so ramping an experiment from 10% to 50% only *adds* users; nobody switches variants or falls
  out.
- **Extensible via SPI, not forks.** `ConfigSource` (where experiments come from), `EventSink`
  (where exposures go), and `TargetingRule` (who is eligible) are small interfaces designed for
  third-party implementations.
- **Virtual threads where they matter.** Assignment is synchronous CPU work and needs no
  threads at all. Virtual threads power the I/O edges: exposure fan-out, SSE config delivery to
  thousands of connected SDKs, and event ingestion — plain blocking code with reactive-level
  scalability.
- **Zero dependencies in the core.** `vexil-core` depends on nothing but the JDK.

## Status & roadmap

Early scaffold — APIs will move. Planned next:

- [ ] HTTP/SSE `ConfigSource` client (SDK side of `vexil-server`)
- [ ] File and JDBC config sources; Kafka and ClickHouse event sinks
- [ ] Mutual-exclusion layers and holdout groups
- [ ] Admin API + persistence for experiment definitions
- [ ] `vexil-stats`: sequential testing / CUPED analysis over collected exposures
- [ ] Cross-language bucketing conformance test vectors

## Requirements

Java 21+ (virtual threads). Build with `mvn verify`.

## License

Apache-2.0
