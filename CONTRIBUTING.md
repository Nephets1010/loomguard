# 贡献指南

感谢你考虑为 `loomguard` 做贡献。这份文档说明本地环境、提交规范，以及 PR 必须通过的检查。

## 本地环境

```bash
git clone https://github.com/Nephets1010/loomguard.git
cd loomguard
./mvnw clean verify
```

只需要 **JDK 21** 与 Maven（`mvnw` 会自动下载 3.9.9）。

> ⚠️ **仓库路径请避免使用非 ASCII 字符。**
> 在 Windows 上，含中文的路径会让 `maven-jar-plugin` 在组装 JAR 时把路径编码搞坏。
> 若遇到 `Error assembling JAR: ... NoSuchFileException` 且路径显示为乱码，把仓库移到纯 ASCII 路径即可。

## 提交规范

[Conventional Commits](https://www.conventionalcommits.org/)：

```
feat: 增加 cpu-bound 任务的 WARN 推荐
fix: ThreadLocalRegistry 并发计数偶发负值
docs: 补充 /actuator/loomguard 字段说明
test: 补 ReentrantLock 不 pinning 的边界用例
```

## PR 必须通过的检查

| check | 内容 | 怎么本地复现 |
|---|---|---|
| `verify (Java 21)` | 全量测试 + 覆盖率门禁 | `./mvnw clean verify` |
| `spotless` | google-java-format（AOSP 风格） | `./mvnw spotless:apply` 后再提交 |
| `architecture-boundaries` | core 零 Spring 依赖 | `./mvnw -pl loomguard-core dependency:list` |

## 代码风格

- 缩进 2 空格（`spotless` 会自动格式化，不要手工对齐）
- **注释与文档用中文**，代码标识符用英文
- 注释解释「**为什么**」，不解释「是什么」

  ```java
  // ✓ 好：解释为什么阈值设为 ZERO —— 否则低于默认 20ms 的短时 pinning 不会被记录
  settings.withThreshold(Duration.ZERO);
  ```

## 架构约束（改代码前必读）

| 约束 | 原因 | 怎么守住 |
|---|---|---|
| **core 不得依赖任何 Spring 类型** | 保证核心检测逻辑可用于任何 JDK 21 应用，不绑定 Spring | CI 的 `architecture-boundaries` job |
| **`loomguard-sample` 不得被其他模块依赖** | demo 只是 demo，不是 API | `maven-enforcer-plugin` 的 `bannedDependencies` |
| **JFR `Recording` 必须 `close()`** | 泄漏的 Recording 会拖慢宿主应用 | `JfrPinningProbe` 的 finally 块 |
| **`ThreadLocal` 字节数只是估算** | 真实占用无法在运行时精确取得 | `ThreadLocalRegistry` 的 `estimatedBytes` 命名与测试 |

## 最需要帮助的方向

### 1. 新增检测规则

在 `RecommendationEngine` 增加确定性规则，并在 `RecommendationEngineTest` 补对应断言。规则必须
**确定**（同一输入恒得同一输出），不依赖时序。

### 2. 扩大端点信息

若需要更多运行时维度，请把数据接入 `LoomGuardCollector`，而非绕过它直接读内部状态。

## 报告 Bug

请用 Issue 模板，务必包含：

- JDK / Spring Boot 版本
- 最小可复现代码
- 实际行为 vs 预期行为
- 若与 pinning 有关：`/actuator/loomguard` 的输出

## 安全问题

不要提 Issue，见 [SECURITY.md](SECURITY.md)。
