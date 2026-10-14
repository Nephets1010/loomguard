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
│   LoomGuard · LoomGuardCollector · LoomGuardEndpoint        │
└───────────────────────────┬──────────────────────────────┘
┌───────────────────────────▼──────────────────────────────┐
│  loomguard-core — pure Java, zero Spring dependencies       │
│   JfrPinningProbe · ThreadLocalRegistry · RecommendationEngine │
└──────────────────────────────────────────────────────────┘
```

The split exists so `loomguard-core` can be used **without Spring** — drop it on the module-path of any
JDK 21 app and call `LoomGuard.inspect(...)` directly.

---

## 6. Configuration quick reference

| Property | Default | Notes |
|---|---|---|
| `loomguard.enabled` | `true` | Set `false` to disable the whole starter |
| `loomguard.expose-endpoint` | `true` | Exposes `/actuator/loomguard` |
| collector ring size | `20` | Bounded recent-findings buffer in `LoomGuardCollector` |

---

## 7. What this is *not*

- ❌ **Not a static analyzer.** It observes real runtime behaviour. Pair it with your linter for the static side.
- ❌ **Not a replacement for `jdk.VirtualThreadPinned` JFR events** — it *uses* them; it just turns them into actionable reports.
- ❌ **No dashboard.** v1 ships one Actuator endpoint. Plug `LoomGuardCollector` into your metrics pipeline if you need more.

---

## 8. Contributing

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
