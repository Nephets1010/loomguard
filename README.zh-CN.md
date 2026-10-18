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
│   LoomGuardAutoConfiguration · LoomGuardCollector · Endpoint │
└───────────────────────────┬──────────────────────────────┘
┌───────────────────────────▼──────────────────────────────┐
│  loomguard-core — 探针 / 解析引擎 / 规则引擎（零 Spring 依赖）│
│   JfrPinningProbe · PinningHotspotCache · RecommendationEngine │
└───────────────────────────┬──────────────────────────────┘
┌───────────────────────────▼──────────────────────────────┐
│  loomguard-contract — 纯类型，零运行时依赖                    │
│   PinningEvent · Finding · Severity · PinningThreshold       │
└──────────────────────────────────────────────────────────┘

   loomguard-mock-jfr — 可复现的合成 JFR 录制（仅测试期依赖）
```

分层的目的有两个：`loomguard-core` 可以**脱离 Spring** 使用 —— 把它丢进任何 JDK 21 应用的 classpath，
直接调 `LoomGuard.inspect(...)`；而 `loomguard-contract` 让「数据类型」与「怎么探测」可以各自演进。

---

## 6. 设计决策

这一节写给「想判断这个库值不值得信」的人。下面每一条都是一个我们做过、并且能讲清楚反例的选择。

### 6.1 为什么用 JFR 事件，而不是 ThreadMXBean 轮询

pinning 的本质是「虚拟线程在临界区里被阻塞，载体线程没法把它卸载」。这件事**只在阻塞发生的那一刻存在**：
一旦阻塞结束、虚拟线程重新挂载，从外部能观测到的状态就恢复如常了。这个特性直接判了轮询方案的死刑：

1. **轮询是抽样，JFR 是事件。** `ThreadMXBean#dumpAllThreads` 一次要遍历全部线程并抓栈，
   代价与线程数成正比；而虚拟线程场景下线程数可以到百万级 —— 你不可能每秒 dump 一百万个线程的栈。
2. **采样必然漏检。** pinning 可能只持续几十微秒。采样周期与事件长度越不匹配，漏检率越高；
   而要提高覆盖率就得堆频率，反过来改变被测系统的时序（观察者效应）—— 偏偏你要测的就是时序敏感的问题。
3. **事件自带现场。** `jdk.VirtualThreadPinned` 事件里直接带着发生栈，不需要再去抓一次栈，
   也不会出现「抓到的栈已经不是阻塞那一刻的栈」这种错配。

代价是必须开一次录制，而录制是有开销的。所以 loomguard 把录制做成**围绕单次任务的短窗口**
（`probe()` 的调用期间），而不是常驻录制 —— 常驻录制等于给生产进程永久加一层 JFR 开销，
那是观测工具不该有的重量。

### 6.2 pinning 的判定口径与误报边界

**判定口径**：事实来源只有一个 —— 录制窗口内是否出现过 `jdk.VirtualThreadPinned` 事件。
出现过，就说明那段代码路径确实会把载体线程钉住。

**默认阈值是「次数 ≥ 1、时长 ≥ 0」**（`PinningThreshold.DEFAULT`），两个数都不是随手取的：

- 次数取 1：一次就足以证明缺陷存在。pinning 是**结构性缺陷**而非程度问题 ——
  一段代码只要在 `synchronized` 里阻塞过一次，它就具备钉住载体线程的能力，与「钉了多久」无关。
- 时长取 0：真实的 `jdk.VirtualThreadPinned` **不带 duration 字段**（JFR 只在被钉住的那一刻打点，
  不记录钉了多久）。如果在这里给一个正的时长阈值，全部真实事件都会因为 `0 >= 阈值` 不成立
  而被判为「不值得关注」—— 那是一次静默的、全域的**漏报**。漏报比误报危险得多：误报会有人来查，漏报不会。

**误报边界**（什么情况下它会说「有 pinning」而其实不用管）：

- **录制窗口内别的线程引起的 pinning 会被计入。** 探针录制的是整个进程在窗口内发生的 pinning，
  不是「只属于你这个任务」的 pinning。并发跑别的任务时，要用 `occurrenceIndex` 与栈自己归因。
- **「被记录」不等于「造成了吞吐损失」。** 阈值刻意设为 0 ms 以求不漏报，
  所以一次 `synchronized` 里哪怕只阻塞 1 微秒也会被记下来。要不要处理，取决于这段代码的调用频率。

**漏报边界**（什么情况下它说「没问题」而其实有问题）：

- pinning 发生在录制窗口之外 —— 探针只在 `probe()` 调用期间录制。
- JVM 关闭了 JFR，或该事件的堆栈被关掉 —— 没有栈的报告只能告诉你「发生了」，定位不了位置。
- 个别 JDK 版本里 native 调用引起的 pinning 不一定会产生该事件。

**热点帧的口径**：`PinningEvent#hotFrame()` 取栈里第一个 `io.github.nephets.*` 帧，找不到就退回栈顶帧。
所以如果你的业务代码不在 `io.github.nephets` 包下，热点帧会退化成栈顶（通常是 JDK 内部帧），
这时要看完整的 `stackTrace()` 自行定位 —— 这个口径刻意写在明处，而不是假装自动归因永远正确。

### 6.3 为什么拆成 5 个模块

| 模块 | 职责 | 为什么单独存在 |
|---|---|---|
| `loomguard-contract` | 事件模型、判定枚举、阈值值对象、异常 | **零运行时依赖**，连 SLF4J 都不引。core 与 starter 只通过这层纯类型对话，任何一方换实现都不会波及对方 |
| `loomguard-mock-jfr` | 可复现的合成 JFR 录制（正常/空/损坏/截断/缺栈/定时） | 让「解析逻辑」不再依赖「JVM 这一刻有没有真的发生 pinning」。依赖方向是 `core(test) → mock-jfr`，**绝不反向** —— 否则 reactor 里两模块互相依赖，拓扑序直接排不出来 |
| `loomguard-core` | 探针、录制解析、规则引擎、热点聚合 | 禁止出现任何 `org.springframework.*`（CI 有 `architecture-boundaries` 门禁）。它是可以脱离 Spring 单独用的那一半 |
| `loomguard-starter` | Spring Boot 自动装配、配置属性、Actuator 端点 | 框架胶水与核心机制分离：换 Boot 大版本不该碰到探针代码 |
| `loomguard-sample` | 可运行的「错误用法 / 正确用法」对照应用 | 文档会过时，能 curl 的应用不会 |

**包名一律保持 `io.github.nephets.loomguard.core.*`**：模块是**物理边界**，不是 API 边界。
拆模块是为了约束依赖方向，不是为了给使用方制造一次重命名迁移 —— 外部使用者的 import 一行都不用改。

### 6.4 已知的局限

- **每次探测都会落一个临时 `.jfr` 文件**（解析完在 `finally` 里删除，删除失败会 WARN 并带上路径）。
  它是为「排障期定向巡检」设计的，不是为「每个请求都跑一遍」设计的；极高频调用会产生文件 IO 压力。
- **`ThreadLocalRegistry` 是进程级单例**，登记条目上限 4096；超过后新名字被拒绝、并累加
  `capacityRejections()`。名字稳定的正常用法永远触不到这个上限。
- **热点缓存容量 512**，超过就按 LRU 淘汰最久未命中的热点，`evictedCount() > 0` 即表示当前视图不完整。
- **单次探测解析出的事件列表本身没有条数上限** —— 加截断会改变观测结果（丢事件），
  需要配合录制时长上限一起设计，见 CHANGELOG 的「已知遗留」。
- **没有仪表盘**，v1 只提供一个 Actuator 端点。
- **临时文件清理 vs 损坏录制**：JDK 21 的 `RecordingFile` 在损坏文件上会在构造方法里抛异常并泄漏文件句柄，
  Windows 上表现为文件被锁住删不掉。因此测试侧对删除是尽力而为，实际文件由系统临时目录策略兜底。

---

## 7. 配置速查

| 属性 | 默认值 | 说明 |
|---|---|---|
| `loomguard.enabled` | `true` | 设为 `false` 关闭整个 starter |
| `loomguard.expose-endpoint` | `true` | 暴露 `/actuator/loomguard` |
| 收集器环形缓冲大小 | `20` | `LoomGuardCollector` 中最近发现的有界缓冲（淘汰会计数） |
| `Clock` bean | `Clock.systemUTC()` | 声明自己的 `Clock` bean 即可整体接管门面与收集器的时间基准 |

---

## 8. 它不是什么

- ❌ **不是静态分析器。** 它观察真实的运行时行为。静态侧请配合你的 linter 使用。
- ❌ **不是 `jdk.VirtualThreadPinned` JFR 事件的替代品** —— 它*使用*这些事件，只是把它们变成可执行的报告。
- ❌ **没有仪表盘。** v1 只提供一个 Actuator 端点。需要更多就把 `LoomGuardCollector` 接入你的 metrics 管线。

---

## 9. 贡献

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
