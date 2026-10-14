<div align="center">

# LoomGuard

**JDK 21 虚拟线程落地防护库 —— 在运行时检测 pinning（载体线程被钉死）、审计 ThreadLocal 内存风险、
发现虚拟线程误用，全部发生在你自己的 Spring Boot 进程内。**

[English](README.md) | [简体中文](README.zh-CN.md)

[![CI](https://github.com/Nephets1010/loomguard/actions/workflows/ci.yml/badge.svg)](https://github.com/Nephets1010/loomguard/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.16-brightgreen.svg)](https://spring.io/projects/spring-boot)

</div>

---

## 1. 它解决什么问题

> 你切到了虚拟线程，吞吐上去了 —— 然后一个 `synchronized` 块、一次 native 调用，或一个从不清理的
> `ThreadLocal` 悄悄把收益吃掉了。
>
> LoomGuard 在你的代码**真正运行**时发现这些问题，并带上真实的栈帧，而不是去源码里 grep。

| 你的困扰 | LoomGuard 做什么 | 证据 |
|---|---|---|
| `synchronized` + 阻塞调用把载体线程钉死 | 通过 JFR 记录 `jdk.VirtualThreadPinned` 并报告出问题的那一帧 | `JfrPinningProbeTest`：sync+sleep → 1 个事件，栈上带你的方法 |
| 你以为 `ReentrantLock` 是安全的 | 确认基于锁的代码**不会** pinning | `JfrPinningProbeTest`：`ReentrantLock`+sleep → 0 个事件 |
| `ThreadLocal` 在每个虚拟线程上无界增长 | `TrackedThreadLocal` 计量估算字节数；`auditThreadLocals()` 排出最糟的 culprit | `ThreadLocalAuditTest` |
| 你不知道某个任务是 pinning / CPU 密集 / 正常 | 确定性的 `RecommendationEngine` 输出 `LOOM-PINNED` / `LOOM-CPU-BOUND` / `LOOM-OK` | `RecommendationEngineTest` |
| 运维在生产看不到 | Actuator 端点 `/actuator/loomguard` 暴露最近发现 | `ProbeControllerIT` |

---

## 2. 30 秒上手

**第一步 —— 加依赖**

```xml
<dependency>
  <groupId>io.github.nephets</groupId>
  <artifactId>loomguard-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

**第二步 —— 检查任意任务**

```java
@Autowired LoomGuard loomGuard;

LoomGuardReport report = loomGuard.inspect(() -> {
    synchronized (lock) {
        Thread.sleep(Duration.ofMillis(50)); // ← 把载体线程钉死
    }
});
// report.hasCritical() == true; ruleId == "LOOM-PINNED"
```

**第三步 —— 或者直接在生产看**

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,loomguard
```

`GET /actuator/loomguard` 返回最近发现、ThreadLocal 估算总字节数，以及最糟的 culprit。

---

## 3. 它能检测到什么

| 能力 | 状态 | 证据（测试类） |
|---|---|---|
| 基于 JFR 的 pinning 检测（进程内，无需 agent） | ✅ | `JfrPinningProbeTest` |
| 穿透辅助方法定位热点帧 | ✅ | `JfrPinningProbeTest#pinningDetectedThroughHelperMethod` |
| `ThreadLocal` 内存风险审计 | ✅ | `ThreadLocalAuditTest`、`ThreadLocalRegistryTest` |
| 确定性推荐规则 | ✅ | `RecommendationEngineTest` |
| Spring Boot 自动装配 + Actuator 端点 | ✅ | `LoomGuardAutoConfigurationTest`、`ProbeControllerIT` |
| `loomguard.enabled` / `loomguard.expose-endpoint` 开关 | ✅ | `LoomGuardAutoConfigurationTest` |

---

## 4. 跑起来试试（sample 应用，无需配置）

```bash
./mvnw -pl loomguard-sample spring-boot:run
```

```bash
curl -s localhost:8080/demo/bad   # synchronized + sleep  → pinning（CRITICAL）
curl -s localhost:8080/demo/good  # ReentrantLock + sleep → 不 pinning（OK）
curl -s localhost:8080/demo/cpu   # CPU 密集             → WARN
curl -s localhost:8080/demo/plain # 阻塞 IO             → OK
curl -s localhost:8080/actuator/loomguard
```

---

## 5. 它在你代码里的位置

```
┌──────────────────────────────────────────────────────────┐
│  你的 Spring Boot 应用                                       │
└───────────────────────────┬──────────────────────────────┘
┌───────────────────────────▼──────────────────────────────┐
│  loomguard-starter — 自动装配 + Actuator 端点               │
│   LoomGuard · LoomGuardCollector · LoomGuardEndpoint        │
└───────────────────────────┬──────────────────────────────┘
┌───────────────────────────▼──────────────────────────────┐
│  loomguard-core — 纯 Java，零 Spring 依赖                   │
│   JfrPinningProbe · ThreadLocalRegistry · RecommendationEngine │
└──────────────────────────────────────────────────────────┘
```

分层的目的：`loomguard-core` 可以**脱离 Spring** 使用 —— 把它丢进任何 JDK 21 应用的 classpath，
直接调 `LoomGuard.inspect(...)`。

---

## 6. 配置速查

| 属性 | 默认值 | 说明 |
|---|---|---|
| `loomguard.enabled` | `true` | 设为 `false` 关闭整个 starter |
| `loomguard.expose-endpoint` | `true` | 暴露 `/actuator/loomguard` |
| 收集器环形缓冲大小 | `20` | `LoomGuardCollector` 中最近发现的有界缓冲 |

---

## 7. 它不是什么

- ❌ **不是静态分析器。** 它观察真实的运行时行为。静态侧请配合你的 linter 使用。
- ❌ **不是 `jdk.VirtualThreadPinned` JFR 事件的替代品** —— 它*使用*这些事件，只是把它们变成可执行的报告。
- ❌ **没有仪表盘。** v1 只提供一个 Actuator 端点。需要更多就把 `LoomGuardCollector` 接入你的 metrics 管线。

---

## 8. 贡献

```bash
git clone https://github.com/Nephets1010/loomguard.git
cd loomguard
./mvnw clean verify
```

PR 必须通过：`verify (Java 21)` · `spotless` · `architecture-boundaries`。

- [CONTRIBUTING.md](CONTRIBUTING.md) · [SECURITY.md](SECURITY.md) · [CHANGELOG.md](CHANGELOG.md)

---

<div align="center">

**Apache-2.0** ｜ [English](README.md) ｜ [简体中文](README.zh-CN.md)

</div>
