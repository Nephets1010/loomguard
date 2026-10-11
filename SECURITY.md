# 安全策略

## 支持版本

| 版本 | 状态 |
|---|---|
| 0.1.x | ✅ 支持 |

## 报告安全漏洞

**请不要用 GitHub Issue 报告安全问题。**

请通过 GitHub 的 [Private vulnerability reporting](https://github.com/Nephets1010/loomguard/security/advisories/new)
提交，或邮件联系维护者。

我们会在收到报告后 **48 小时内**确认收到，**7 个工作日内**给出修复计划或缓解方案。

## 我们特别关注的问题

作为一款在进程内采集运行时信息的库，以下几类问题我们会**优先处理**：

| 类型 | 为什么重要 |
|---|---|
| **JFR 采集的副作用** | `JfrPinningProbe` 在进程内开关 `Recording`，任何泄漏的 `Recording` 或过大的 dump 都会拖慢宿主应用 |
| **`ThreadLocal` 计量估算失真** | 估算字节数只是「估算」，若被当成真实内存占用做决策，可能误导容量规划 |
| **端点信息泄露** | `/actuator/loomguard` 默认暴露最近发现；若被未经认证地暴露到公网，可能泄露内部栈帧 |
| **依赖投毒** | 本库依赖极少，但仍需防范传递依赖引入的恶意代码 |

## 关于端点的使用建议

- **不要**把 `loomguard.expose-endpoint` 在公网环境保持开启，除非 Actuator 已被 Spring Security 保护
- 默认 `management.endpoints.web.exposure.include` 不包含 `loomguard`；只有显式加入才会暴露
- 栈帧信息可能含类名与方法名，属于内部实现细节，按内部接口对待

## 依赖安全

框架版本锁定为 Spring Boot 3.5.16（3.5 线最后一个 OSS 补丁）。CI 通过 `architecture-boundaries` job
机器化守护 `loomguard-core` 零 Spring 依赖，避免核心逻辑被框架内部 API 绑定。
