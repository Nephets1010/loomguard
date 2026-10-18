/*
 * Copyright 2026 The loomguard Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.nephets.loomguard.starter;

import io.github.nephets.loomguard.core.Finding;
import io.github.nephets.loomguard.core.LoomGuard;
import io.github.nephets.loomguard.core.Severity;
import io.github.nephets.loomguard.core.ThreadLocalEntry;
import io.github.nephets.loomguard.core.ThreadLocalReport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.List;
import java.util.Objects;

/** Actuator 端点 {@code /actuator/loomguard}：暴露启用状态、线程本地内存审计与最近发现。 */
@Endpoint(id = "loomguard")
public class LoomGuardEndpoint {

    private static final Logger log = LoggerFactory.getLogger(LoomGuardEndpoint.class);

    /** 端点一次最多回显的发现条数：防止最近结果里有海量 CRITICAL 时把响应体撑爆。 */
    private static final int MAX_RECENT_FINDINGS = 20;

    private final LoomGuardProperties properties;
    private final LoomGuard loomGuard;
    private final LoomGuardCollector collector;

    public LoomGuardEndpoint(
            LoomGuardProperties properties, LoomGuard loomGuard, LoomGuardCollector collector) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.loomGuard = Objects.requireNonNull(loomGuard, "loomGuard must not be null");
        this.collector = Objects.requireNonNull(collector, "collector must not be null");
    }

    /**
     * 读取当前快照。
     *
     * <p><strong>为什么这个方法永不抛异常：</strong>它是一个<strong>观测端点</strong>。观测设施存在的意义是在系统出问题的时候
     * 还能给出信息；如果它自己在系统出问题时先崩了，那它就是最没用的那类设施 —— 而且它的调用方（Actuator 的 HTTP 层、JMX、健康检查）往往没有准备好处理业务异常，一次
     * NPE 会直接变成一个 500， 把「我想看看系统怎么了」变成「系统又多了一个故障」。因此这里对每个数据来源单独降级：
     * 审计失败就当「没有线程本地数据」，收集器失败就当「没有最近发现」，其余字段照常返回。
     *
     * <p><strong>为什么只捕获 {@code RuntimeException} 而不捕获 {@code Throwable}：</strong>{@code Error}
     * （{@code OutOfMemoryError}、{@code StackOverflowError}）代表 JVM 已经处于不适宜继续运行的状态，
     * 把它吞掉只会让进程带着一个坏状态继续跑、然后在别处更莫名其妙地崩掉。可预期的失败才降级， 不可预期的失败必须暴露 —— 这是「降级」与「掩盖」的分界线。
     */
    @ReadOperation
    public LoomGuardSnapshot read() {
        log.info("读取 loomguard 端点快照：enabled={}", properties.isEnabled());
        ThreadLocalReport tl = auditQuietly();
        List<ThreadLocalItem> top = toItems(tl.entries());
        List<RecentFinding> recent = recentFindingsQuietly();
        return new LoomGuardSnapshot(
                properties.isEnabled(), tl.totalEstimatedBytes(), tl.distinctLocals(), top, recent);
    }

    private ThreadLocalReport auditQuietly() {
        try {
            return loomGuard.auditThreadLocals();
        } catch (RuntimeException e) {
            log.error("线程本地审计失败，端点按空审计降级（不影响其余字段）", e);
            return ThreadLocalReport.of(0L, 0, List.of());
        }
    }

    private List<RecentFinding> recentFindingsQuietly() {
        try {
            return toRecent(
                    collector.recent().stream()
                            .flatMap(r -> r.findings().stream())
                            .limit(MAX_RECENT_FINDINGS)
                            .toList());
        } catch (RuntimeException e) {
            log.error("读取最近巡检结果失败，端点按空发现降级（不影响其余字段）", e);
            return List.of();
        }
    }

    private static List<ThreadLocalItem> toItems(List<ThreadLocalEntry> entries) {
        return entries.stream()
                .map(
                        e ->
                                new ThreadLocalItem(
                                        e.name(),
                                        e.bytesPerThread(),
                                        e.setCallCount(),
                                        e.estimatedBytes()))
                .toList();
    }

    private static List<RecentFinding> toRecent(List<Finding> findings) {
        return findings.stream()
                .map(
                        f ->
                                new RecentFinding(
                                        f.severity().name(),
                                        f.ruleId(),
                                        f.message(),
                                        f.suggestedFix()))
                .toList();
    }

    /** 端点快照 DTO。 */
    public record LoomGuardSnapshot(
            boolean enabled,
            long totalEstimatedBytes,
            int distinctLocals,
            List<ThreadLocalItem> topThreadLocals,
            List<RecentFinding> recentFindings) {}

    /** 线程本地内存项。 */
    public record ThreadLocalItem(
            String name, long bytesPerThread, long setCallCount, long estimatedBytes) {}

    /** 最近发现项。 */
    public record RecentFinding(
            String severity, String ruleId, String message, String suggestedFix) {}

    /** 便于测试构造预填充的快照。 */
    static LoomGuardSnapshot of(boolean enabled, ThreadLocalReport tl, List<Finding> findings) {
        return new LoomGuardSnapshot(
                enabled,
                tl.totalEstimatedBytes(),
                tl.distinctLocals(),
                toItems(tl.entries()),
                toRecent(findings));
    }

    /** 测试辅助：构造一个 CRITICAL 发现，避免在测试中硬编码字符串。 */
    static Finding critical(String ruleId, String message) {
        return Finding.builder(Severity.CRITICAL, ruleId).message(message).build();
    }
}
