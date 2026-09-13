# Vexil

A modern, extensible A/B testing and experimentation framework for the JVM, built on Java 21+ virtual threads.

Vexil is layered so each piece is useful on its own:

| Module | What it is |
|---|---|
| `vexil-core` | Zero-dependency assignment engine: deterministic Murmur3 bucketing, weighted variants, traffic ramps, mutual-exclusion layers, holdout groups, targeting rules, async exposure tracking. Embed it in any JVM app. |
| `vexil-wire` | The shared JSON wire format exchanged between server and SDKs. |
| `vexil-server` | Self-hostable config delivery (SSE), exposure ingestion, admin API, JSON-file persistence. One virtual thread per connection. |
| `vexil-client` | SDK-side `ConfigSource` (SSE with reconnect + polling fallback) and `EventSink` speaking to `vexil-server`. |
| `vexil-sink-jdbc` | Exposure events into any JDBC database (Postgres, ClickHouse, MySQL, …). |
| `vexil-sink-kafka` | Exposure events onto a Kafka topic as JSON, keyed by unit id. |

## Quick start (embedded, no server)

```java
var source = new InMemoryConfigSource(List.of(
    Experiment.running("checkout-cta",
        Variant.of("control", 1),
        Variant.of("treatment", 1))));

try (var engine = new ExperimentEngine(source, List.of(mySink))) {
    Assignment a = engine.evaluate("checkout-cta", EvaluationContext.of(userId));
    if (a.enrolled() && a.variantKey().equals("treatment")) {
        // render the new call-to-action
    }
}
```

Evaluation is pure CPU — a few hashes and a table walk — and safe on any hot path. Exposure
events are batched and delivered to your `EventSink`s from a dedicated virtual thread, never
blocking the request.

## Quick start (client/server)

Run the server (experiments persist in `vexil-config.json`, editable via the admin API):

```bash
mvn package
VEXIL_ADMIN_TOKEN=change-me java -cp ... io.vexil.server.Main 8080
```

Manage experiments over HTTP:

```bash
curl -X PUT localhost:8080/api/experiments/checkout-cta \
  -H "Authorization: Bearer change-me" \
  -d '{"key":"checkout-cta","status":"RUNNING","trafficAllocation":0.5,
       "variants":[{"key":"control","weight":1},{"key":"treatment","weight":1}]}'
```

Consume from your application — assignments update in near real time via SSE:

```java
try (var source = new HttpConfigSource("http://experiments.internal:8080");
     var engine = new ExperimentEngine(source, List.of(
         new HttpEventSink("http://experiments.internal:8080")))) {
    Assignment a = engine.evaluate("checkout-cta", EvaluationContext.of(userId));
}
```

## Design principles

- **Determinism is a contract.** The same unit gets the same variant everywhere, forever:
  Murmur3-32 over `salt:unitId`, UTF-8 encoded. This algorithm is versioned and never changes
  silently — it is what makes cross-language SDKs possible.
- **Ramp-safe enrollment.** Traffic allocation and variant choice use independent hash points,
  so ramping an experiment from 10% to 50% only *adds* users; nobody switches variants or falls
  out.
- **Run many experiments safely.** Experiments sharing a layer split a common bucket space into
  disjoint slices for mutual exclusion; global holdout groups withhold a clean baseline
  population from all experiments.
- **Extensible via SPI, not forks.** `ConfigSource` (where experiments come from), `EventSink`
  (where exposures go), and `TargetingRule` (who is eligible) are small interfaces designed for
  third-party implementations.
- **Virtual threads where they matter.** Assignment is synchronous CPU work and needs no
  threads at all. Virtual threads power the I/O edges: exposure fan-out, SSE config delivery to
  thousands of connected SDKs, and event ingestion — plain blocking code with reactive-level
  scalability.
- **Zero dependencies in the core.** `vexil-core` depends on nothing but the JDK.

## Status & roadmap

Early but functional — the full loop works: define experiments via the admin API, serve them
over SSE, assign deterministically in-process, and ship exposures to JDBC/Kafka. APIs may still
move before 1.0. Planned next:

- [ ] `vexil-stats`: sequential testing / CUPED analysis over collected exposures
- [ ] Admin UI
- [ ] Cross-language bucketing conformance test vectors (for future non-JVM SDKs)
- [ ] Segment definitions shared across experiments
- [ ] Metrics/OpenTelemetry integration

## Requirements

Java 21+ (virtual threads). Build with `mvn verify`.

## License

Apache-2.0
