<div align="center">

# LoomGuard

**A JDK 21 virtual-thread guard rail — catch pinning, audit ThreadLocal memory risk, and flag
virtual-thread misuse at runtime, inside your own Spring Boot process.**

[English](README.md) | [简体中文](README.zh-CN.md)

[![CI](https://github.com/Nephets1010/loomguard/actions/workflows/ci.yml/badge.svg)](https://github.com/Nephets1010/loomguard/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-brightgreen.svg)](https://spring.io/projects/spring-boot)

</div>

---

## 1. The problem this solves

> You moved to virtual threads. Throughput went up — and then a `synchronized` block, a native call, or a
> `ThreadLocal` that never gets cleaned up quietly ate the win.
>
> LoomGuard finds those problems **while your code runs**, with the real stack frames, not a grep over the source.

| Your problem | What LoomGuard does | Proof |
|---|---|---|
| `synchronized` + blocking call pins the carrier thread | Records `jdk.VirtualThreadPinned` via JFR and reports the offending frame | `JfrPinningProbeTest`: sync+sleep → 1 event with your method on the stack |
| You assumed `ReentrantLock` is safe | Confirms lock-based code does **not** pin | `JfrPinningProbeTest`: `ReentrantLock`+sleep → 0 events |
| `ThreadLocal` grows unbounded per virtual thread | `TrackedThreadLocal` meters estimated bytes; `auditThreadLocals()` ranks the worst offenders | `ThreadLocalAuditTest` |
| You don't know if a task is pinning / CPU-bound / fine | Deterministic `RecommendationEngine` emits `LOOM-PINNED` / `LOOM-CPU-BOUND` / `LOOM-OK` | `RecommendationEngineTest` |
| Ops can't see it in production | Actuator endpoint `/actuator/loomguard` exposes the latest findings | `ProbeControllerIT` |

---

## 2. 30-second quickstart

**Step 1 — add the dependency**

```xml
<dependency>
  <groupId>io.github.nephets</groupId>
  <artifactId>loomguard-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

**Step 2 — inspect any task**

```java
@Autowired LoomGuard loomGuard;

LoomGuardReport report = loomGuard.inspect(() -> {
    synchronized (lock) {
        Thread.sleep(Duration.ofMillis(50)); // ← pins the carrier thread
    }
});
// report.hasCritical() == true; ruleId == "LOOM-PINNED"
```

**Step 3 — or just watch it in production**

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,loomguard
```

`GET /actuator/loomguard` returns the latest findings, total estimated `ThreadLocal` bytes, and the top
offenders.

---

## 3. What it detects

| Capability | Status | Evidence (test class) |
|---|---|---|
| JFR-based pinning detection (process-internal, no agent) | ✅ | `JfrPinningProbeTest` |
| Hot-frame attribution through helper methods | ✅ | `JfrPinningProbeTest#pinningDetectedThroughHelperMethod` |
| `ThreadLocal` memory-risk audit | ✅ | `ThreadLocalAuditTest`, `ThreadLocalRegistryTest` |
| Deterministic recommendation rules | ✅ | `RecommendationEngineTest` |
| Spring Boot auto-configuration + Actuator endpoint | ✅ | `LoomGuardAutoConfigurationTest`, `ProbeControllerIT` |
| `loomguard.enabled` / `loomguard.expose-endpoint` switches | ✅ | `LoomGuardAutoConfigurationTest` |

---

## 4. Try it (sample app, no setup)

```bash
./mvnw -pl loomguard-sample spring-boot:run
```

```bash
curl -s localhost:8080/demo/bad   # synchronized + sleep  → pinning (CRITICAL)
curl -s localhost:8080/demo/good  # ReentrantLock + sleep → not pinning (OK)
curl -s localhost:8080/demo/cpu   # CPU-bound             → WARN
curl -s localhost:8080/demo/plain # blocking IO           → OK
curl -s localhost:8080/actuator/loomguard
```

---

## 5. Where it sits in your code

```
┌──────────────────────────────────────────────────────────┐
│  Your Spring Boot app                                       │
└───────────────────────────┬──────────────────────────────┘
┌───────────────────────────▼──────────────────────────────┐
│  loomguard-starter — auto-config + Actuator endpoint        │
│   LoomGuardAutoConfiguration · Collector · Endpoint         │
└───────────────────────────┬──────────────────────────────┘
┌───────────────────────────▼──────────────────────────────┐
│  loomguard-core — probe / parser / rules (zero Spring)      │
│   JfrPinningProbe · PinningHotspotCache · RecommendationEngine │
└───────────────────────────┬──────────────────────────────┘
┌───────────────────────────▼──────────────────────────────┐
│  loomguard-contract — pure types, zero runtime deps         │
│   PinningEvent · Finding · Severity · PinningThreshold      │
└──────────────────────────────────────────────────────────┘

   loomguard-mock-jfr — reproducible synthetic JFR recordings (test scope)
```

The split buys two things: `loomguard-core` can be used **without Spring** — drop it on the classpath of
any JDK 21 app and call `LoomGuard.inspect(...)` directly — and `loomguard-contract` lets the *data types*
and the *detection machinery* evolve independently.

---

## 6. Design decisions

This section is for people deciding whether to trust this library. Every item below is a choice we
made *and* can argue for with a counter-example.

### 6.1 Why JFR events and not `ThreadMXBean` polling

Pinning means "a virtual thread is blocked inside a critical section and its carrier cannot unmount it".
That condition **only exists at the moment of the block**: once the block ends and the virtual thread
remounts, everything observable from outside looks normal again. That single fact rules out polling:

1. **Polling samples, JFR records events.** `ThreadMXBean#dumpAllThreads` walks every thread and captures
   its stack — cost grows with thread count, and virtual threads scale to millions. You cannot dump a
   million stacks every second.
2. **Sampling misses events.** A pin can last tens of microseconds. The more the sampling period and the
   event length diverge, the higher the miss rate; raising the frequency to compensate changes the very
   timing you are trying to measure (the observer effect) — and timing is exactly what pinning is about.
3. **The event carries its own context.** `jdk.VirtualThreadPinned` ships the stack that was live at the
   moment of the pin, so there is no "the stack I captured is not the stack that blocked" mismatch.

The cost is that a recording must be running. That is why loomguard records in a **short window around a
single task** (the duration of one `probe()` call) instead of running a permanent recording — a permanent
recording adds a standing JFR overhead to a production process, which is more weight than an observation
tool is entitled to carry.

### 6.2 The pinning verdict — what it means, and where it lies

**The verdict.** There is exactly one source of truth: did a `jdk.VirtualThreadPinned` event occur inside
the recording window? If yes, that code path demonstrably pins its carrier thread.

**The default threshold is "count ≥ 1, duration ≥ 0"** (`PinningThreshold.DEFAULT`). Neither number is
arbitrary:

- **Count = 1** because a single occurrence already proves the defect. Pinning is a *structural* defect,
  not a matter of degree: code that blocked once inside `synchronized` has the ability to pin a carrier,
  regardless of how long it pinned.
- **Duration = 0** because the real `jdk.VirtualThreadPinned` **has no `duration` field** — JFR only marks
  the instant of the pin, not its length. Any positive duration threshold would make every real event fail
  `0 >= threshold` and be classified "not worth reporting" — a silent, repo-wide **false-negative outbreak**.
  False negatives are far more dangerous than false positives: a false positive gets investigated, a false
  negative does not.

**False-positive boundary** (cases where it says "pinned" but you may not care):

- **Pins caused by *other* threads in the window are counted too.** The probe records every pin in the
  process during the window, not "pins belonging to your task". When running concurrently, attribute by
  `occurrenceIndex` and the stack yourself.
- **"Recorded" ≠ "caused throughput loss".** The threshold is deliberately 0 ms so nothing is missed, so a
  `synchronized` block that blocked for one microsecond is still recorded. Whether to act depends on how
  often that path runs.

**False-negative boundary** (cases where it says "fine" but it is not):

- The pin happened outside the recording window — the probe only records during `probe()`.
- JFR is disabled, or stack traces were switched off for that event — a stack-less report can tell you
  "it happened" but not "where".
- On some JDK builds, pins caused by native calls do not always emit this event.

**Hot-frame semantics.** `PinningEvent#hotFrame()` returns the first `io.github.nephets.*` frame on the
stack, falling back to the top frame. If your code does not live under that package, the hot frame
degrades to the top (usually a JDK-internal) frame and you should read the full `stackTrace()`. This
limitation is documented rather than hidden behind a promise of automatic attribution.

### 6.3 Why five modules

| Module | Responsibility | Why it exists separately |
|---|---|---|
| `loomguard-contract` | Event model, verdict enum, threshold value object, exceptions | **Zero runtime dependencies** — not even SLF4J. core and starter talk only through these pure types, so either side can change implementation without touching the other |
| `loomguard-mock-jfr` | Reproducible synthetic JFR recordings (normal / empty / corrupt / truncated / stack-less / timed) | Frees the **parser** from depending on whether the JVM happened to pin something right now. Dependency direction is `core(test) → mock-jfr` and **never the reverse** — otherwise the two modules form a cycle the reactor cannot topologically sort |
| `loomguard-core` | Probe, recording parser, rule engine, hotspot aggregation | No `org.springframework.*` allowed (CI enforces it via the `architecture-boundaries` job). This is the half that works without Spring |
| `loomguard-starter` | Spring Boot auto-configuration, properties, Actuator endpoint | Framework glue kept apart from core mechanics: upgrading Boot should never touch probe code |
| `loomguard-sample` | Runnable "wrong way / right way" comparison app | Docs go stale; an app you can `curl` does not |

**Package names stay `io.github.nephets.loomguard.core.*`.** Modules are a **physical boundary**, not an API
boundary. Splitting modules is about constraining dependency direction, not about forcing a rename
migration on users — no import has to change.

### 6.4 Known limitations

- **Every probe writes a temporary `.jfr` file** (deleted in a `finally`; a failed delete is logged at WARN
  with the path). It is designed for *targeted inspection while diagnosing*, not for running on every
  request — very high call rates will create file-IO pressure.
- **`ThreadLocalRegistry` is a process-wide singleton** capped at 4096 entries; beyond that, new names are
  rejected and `capacityRejections()` increments. Stable names never reach the cap.
- **The hotspot cache holds 512 entries** and evicts least-recently-used ones; `evictedCount() > 0` means
  the view you are looking at is incomplete.
- **The parsed event list from a single probe is itself unbounded** — truncating it would change the
  observation (dropping events), so it needs to be designed together with a recording-duration cap. See
  "Known leftovers" in the CHANGELOG.
- **No dashboard.** v1 ships one Actuator endpoint.
- **Temp-file cleanup vs. corrupt recordings:** JDK 21's `RecordingFile` throws from its constructor on a
  corrupt file and leaks the handle, which on Windows means the file stays locked and undeletable. Tests
  therefore delete on a best-effort basis and let the OS temp-directory policy reclaim the rest.

---

## 7. Configuration quick reference

| Property | Default | Notes |
|---|---|---|
| `loomguard.enabled` | `true` | Set `false` to disable the whole starter |
| `loomguard.expose-endpoint` | `true` | Exposes `/actuator/loomguard` |
| collector ring size | `20` | Bounded recent-findings buffer in `LoomGuardCollector` (evictions are counted) |
| `Clock` bean | `Clock.systemUTC()` | Declare your own `Clock` bean to take over the time base for both the facade and the collector |

---

## 8. What this is *not*

- ❌ **Not a static analyzer.** It observes real runtime behaviour. Pair it with your linter for the static side.
- ❌ **Not a replacement for `jdk.VirtualThreadPinned` JFR events** — it *uses* them; it just turns them into actionable reports.
- ❌ **No dashboard.** v1 ships one Actuator endpoint. Plug `LoomGuardCollector` into your metrics pipeline if you need more.

---

## 9. Contributing

```bash
git clone https://github.com/Nephets1010/loomguard.git
cd loomguard
./mvnw clean verify
```

PRs must pass: `verify (Java 21)` · `spotless` · `architecture-boundaries`.

- [CONTRIBUTING.md](CONTRIBUTING.md) · [SECURITY.md](SECURITY.md) · [CHANGELOG.md](CHANGELOG.md)

---

<div align="center">

**Apache-2.0** ｜ [English](README.md) ｜ [简体中文](README.zh-CN.md)

</div>
