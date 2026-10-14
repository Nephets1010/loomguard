# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

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
