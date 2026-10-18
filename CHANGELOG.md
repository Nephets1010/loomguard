# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased] — 2026-10-18

T0 制作水准升级：模块 3 → 5、测试 102 → 292、Clock 注入、观测路径降级、有界集合可观测化、
设计决策章节。**未改动任何 public 方法签名 / 返回类型 / 类名 / 包名**，使用方 import 一行都不用改。

### 新增

#### 模块拆分 3 → 5

- 新增 `loomguard-contract`：事件模型（`PinningEvent` / `PinningReport`）、判定结果枚举（`Severity`）、
  发现项（`Finding`）、阈值值对象（`PinningThreshold`）、异常（`LoomGuardException`）、
  `TaskSpec` / `TaskObservation` / `ThreadLocalEntry` / `ThreadLocalReport` / `LoomGuardConfig` /
  `LoomGuardReport`。**零运行时依赖**（连 SLF4J 都不引），core 与 starter 只通过这层纯类型对话
- 新增 `loomguard-mock-jfr`：用 `jdk.jfr` 录出**内容确定**的合成 `.jfr` 文件（正常 / 空 / 损坏 /
  截断 / 缺栈 / 带时长），让 core 的解析测试不再依赖「宿主 JVM 这一刻有没有真的发生 pinning」
- 包名与全限定类名一律保持 `io.github.nephets.loomguard.core.*`：只移动物理目录 + 调整 pom 依赖

#### Clock 注入（家族统一纪律）

- `JfrPinningProbe` / `LoomProbe` / `LoomGuard` / `LoomGuardCollector` 一律在构造期接收 `java.time.Clock`，
  生产用 `Clock.systemUTC()`，测试用固定时钟
- `LoomGuardReport.of(..., Instant observedAt)` 显式化观测时刻；`LoomGuardCollector.recentWithin(Duration)`
  的时间窗口判定从此可确定性断言（闭区间：`now - observedAt <= window` 算命中）
- 自动装配新增 `@ConditionalOnMissingBean Clock` bean：使用方声明自己的 `Clock` 即可整体接管时间基准
- **为什么不改 `LoomProbe` 的耗时计量**：墙钟耗时仍用 `System.nanoTime()`。它是单调时钟，
  不受 NTP 校时 / 手动改表影响；用墙上时间测耗时，一次时钟回拨就能让耗时变成负数

#### 可观测性纪律

- 「观测路径永不抛异常」：`LoomGuardEndpoint#read()` 对每个数据来源单独降级（审计失败 → 空审计，
  收集器失败 → 空发现）。只捕获 `RuntimeException` 而不捕获 `Error` —— 可预期的失败才降级，
  不可预期的失败必须暴露，这是「降级」与「掩盖」的分界线
- 构造期拒绝 null：`PinningThreshold` 负数入参抛 `LoomGuardException`（专属类型而非 IAE，
  让「loomguard 自己的契约被打破」能被一个 catch 隔离出来）
- 长生命周期集合一律有界且淘汰可观测：
  - 新增 `PinningHotspotCache`：跨巡检聚合 pinning 热点，有界 LRU（512），
    淘汰打 WARN + 累加 `evictedCount()`。用 LRU 而不是「满了拒绝写入」，
    是因为后者会让缓存稳定停留在「开机头几分钟看到的那一批热点」上，变成一个说谎的视图
  - `ThreadLocalRegistry` 新增 `capacityRejections()`：被拒绝的登记要能计数，
    否则泄漏只会以「审计数字偏小」的形式悄悄出现
  - `LoomGuardCollector` 新增 `evictedCount()`
  - `JfrPinningProbe` 单个事件的栈截断到 `MAX_STACK_FRAMES = 64`，截断打 WARN + 累加
    `truncatedStackTraces()`

#### 离线解析能力

- `JfrPinningProbe#parse(Path)`：直接解析一个已存在的 `.jfr` 文件。
  探针自己也走这个方法，全库只剩一份解析实现 —— 「离线分析运维拷回来的录制」与「在线探测」
  结论天然一致
- 时长字段按「有则取、无则降级为 0」处理：真实 `jdk.VirtualThreadPinned` 是瞬时事件，
  实测中 JFR 给每个事件都附 `duration` 字段（瞬时事件值为 0），定时事件（begin/end）才带真实值

#### 测试 102 → 292

| 类别 | 数量（约） | 代表用例 |
|---|---|---|
| JFR 录制解析边界（正常 / 空 / 损坏 / 截断 / 缺栈 / 超长栈 / 事件名过滤） | 21 | `JfrPinningProbeParseTest` |
| Clock / 时间窗口 | 18 | `JfrPinningProbeClockTest`、`LoomGuardClockTest`、`LoomGuardCollectorWindowTest` |
| 契约测试（equals / hashCode / toString / 构造期 null 拒绝 / 防御性拷贝） | 60+ | contract 模块各 `*Test`、`FindingBuilderTest`、`SeverityOrderingTest` |
| 阈值边界（0 / 差一 / 恰好达标 / 极大值 / 负数拒绝） | 33 | `PinningThresholdTest`、`RecommendationEngineThresholdTest` |
| 有界集合与淘汰可观测 | 19 | `PinningHotspotCacheTest`、`ThreadLocalRegistryEvictionTest` |
| starter 自动装配 / 属性绑定 / 端点降级 | 39 | `LoomGuardPropertiesTest`、`LoomGuardEndpointDegradationTest` |
| 合成录制自身可复现 | 15 | `SyntheticPinningRecordingTest` |

### 变更

- `RecommendationEngine#recommend(TaskObservation, PinningThreshold)` 新增重载：
  原单参方法委托给默认阈值 `PinningThreshold.DEFAULT`（次数 ≥ 1、时长 ≥ 0），行为完全不变
- `LoomGuardConfig` 补 `equals` / `hashCode` / `toString`（值对象本该有值语义）
- `LoomGuard` 现在持有 `LoomProbe` 与 `PinningHotspotCache` 实例（此前每次巡检新建 `LoomProbe`，
  跨巡检的热点聚合因此退化成单次快照）
- CI `architecture-boundaries` job 先 `install` 再 `dependency:list`：core 现在依赖
  `loomguard-contract`（compile）与 `loomguard-mock-jfr`（test），
  只跑 `-pl loomguard-core` 时这两个 SNAPSHOT 解析不到，门禁会以「找不到 artifact」失败 ——
  那不是「边界完好」，是门禁压根没跑起来

### 文档

- README.md / README.zh-CN.md 新增「设计决策」章节：① 为什么用 JFR 事件而不是 ThreadMXBean 轮询；
  ② pinning 的判定口径与误报 / 漏报边界；③ 为什么拆成 5 个模块；④ 已知的局限

### 已知遗留（只记录不实施）

- **单次探测解析出的 `PinningEvent` 列表本身无条数上限**：加截断会改变观测结果（丢事件），
  需配合录制时长上限一起设计，属破坏性变更
- **`ThreadLocalRegistry` 的上界是固定常量 4096，未做成可配置**：加配置需改公开构造 / 静态工厂签名
- **`LoomProbe#observe` 用 `catch (Throwable)` 兜底整个探测链并按空报告降级**：
  收窄为具体异常会改变「观测永不抛出」的契约
- **`duration` 是 JFR 保留字段名**：实测（JDK 21）在事件类里声明名为 `duration` 的字段
  （无论是否加 `@Timespan`）会导致该事件**再也录不进任何录制**（`readAllEvents` 恒返回 0 条且不报错）。
  想要「带时长」的合成事件只能靠 `begin()/end()` 让 JVM 自己附上 —— 已写进 `SyntheticPinnedEvent` 的类注释
- **JDK 21 的 `RecordingFile` 在损坏文件上会在构造方法里抛 `IOException` 并泄漏文件句柄**：
  Windows 上表现为文件被锁住删不掉。测试侧因此对删除做尽力而为，不做 `@TempDir` 强清理

## [0.1.0] — 2026-10-11

首个可发布版本。

### 新增

#### 虚拟线程 pinning 检测（JFR，进程内）

- `JfrPinningProbe` 在进程内开关 `Recording`，启用 `jdk.VirtualThreadPinned` 事件，
  阈值 `Duration.ZERO`，带栈帧；任务跑完即 `dump` + `close`
- 实测（原型 + `JfrPinningProbeTest`）：`synchronized` + `Thread.sleep` → 1 个事件且栈帧含业务方法；
  `ReentrantLock` + `Thread.sleep` → 0 个事件
- 明确：`Object.wait()` 在 `synchronized` 内**不** pinning（wait 释放 monitor），只有持有 monitor
  时的 `sleep` / IO 才 pinning

#### ThreadLocal 内存风险审计

- `TrackedThreadLocal` 继承 `ThreadLocal`，`set` 时向 `ThreadLocalRegistry` 上报估算字节数
- `ThreadLocalRegistry` 单例累计 `bytesPerThread` / `setCallCount`，`snapshot` 按估算字节数降序
- `auditThreadLocals()` 生成 `ThreadLocalReport`，排出最糟 culprit

#### 确定性推荐引擎

- `RecommendationEngine` 输出 `LOOM-PINNED` / `LOOM-PINNED-BLOCKING-IO` / `LOOM-CPU-BOUND` / `LOOM-OK`
- `worst(List<Finding>)` 取最严重的一条，供端点汇总

#### 嵌入式 starter

- `LoomGuardAutoConfiguration`：`@ConditionalOnProperty(loomguard.enabled)` + 暴露端点再套
  `loomguard.expose-endpoint`
- `LoomGuardCollector`：有界 `ArrayDeque`（默认 20）收集最近发现
- `LoomGuardEndpoint`：`/actuator/loomguard` 返回 enabled / 总估算字节 / 不同 ThreadLocal 数 /
  最糟 culprit / 最近发现

#### 工程

- core 层零 Spring 依赖，CI 用 `architecture-boundaries` job 机器化守护
- 覆盖率门禁：core ≥ 75% · 其余 ≥ 60%
- 三模块共 **84** 个测试，断言真实行为；sample 的 `ProbeControllerIT` 端到端验证探针能区分
  pinning / 非 pinning / CPU 密集 / 阻塞 IO
- 纯 JDK 21 + 进程内 JFR，CI 无 Docker 依赖

### 已知限制

- **pinning 检测依赖 JFR**：需要 JDK 的 JFR 能力（默认开启），且 `Recording` 在进程内开关有极轻量开销
- **ThreadLocal 字节数是估算值**，不是真实占用；决策时不要当成精确数字
- **无仪表盘**，v1 仅一个 Actuator 端点
- **Spring Boot 3.5 的 OSS 支持已于 2026-06-30 结束**，本项目锁定 3.5.16

### 破坏性变更预留

对外 API 遵循：新增签名变化升 MINOR，删除/不兼容变化升 MAJOR，并在本文件写「迁移指南」。

<!-- 上传通道校验标记 -->
