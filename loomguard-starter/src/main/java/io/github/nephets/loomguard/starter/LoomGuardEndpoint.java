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
import io.github.nephets.loomguard.core.ThreadLocalReport;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.util.List;

/** Actuator 端点 {@code /actuator/loomguard}：暴露启用状态、线程本地内存审计与最近发现。 */
@Endpoint(id = "loomguard")
public class LoomGuardEndpoint {

    private final LoomGuardProperties properties;
    private final LoomGuard loomGuard;
    private final LoomGuardCollector collector;

    public LoomGuardEndpoint(
            LoomGuardProperties properties, LoomGuard loomGuard, LoomGuardCollector collector) {
        this.properties = properties;
        this.loomGuard = loomGuard;
        this.collector = collector;
    }

    /** 读取当前快照。 */
    @ReadOperation
    public LoomGuardSnapshot read() {
        ThreadLocalReport tl = loomGuard.auditThreadLocals();
        List<ThreadLocalItem> top =
                tl.entries().stream()
                        .map(
                                e ->
                                        new ThreadLocalItem(
                                                e.name(),
                                                e.bytesPerThread(),
                                                e.setCallCount(),
                                                e.estimatedBytes()))
                        .toList();
        List<RecentFinding> recent =
                collector.recent().stream()
                        .flatMap(r -> r.findings().stream())
                        .limit(20)
                        .map(
                                f ->
                                        new RecentFinding(
                                                f.severity().name(),
                                                f.ruleId(),
                                                f.message(),
                                                f.suggestedFix()))
                        .toList();
        return new LoomGuardSnapshot(
                properties.isEnabled(), tl.totalEstimatedBytes(), tl.distinctLocals(), top, recent);
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
        List<ThreadLocalItem> top =
                tl.entries().stream()
                        .map(
                                e ->
                                        new ThreadLocalItem(
                                                e.name(),
                                                e.bytesPerThread(),
                                                e.setCallCount(),
                                                e.estimatedBytes()))
                        .toList();
        List<RecentFinding> recent =
                findings.stream()
                        .map(
                                f ->
                                        new RecentFinding(
                                                f.severity().name(),
                                                f.ruleId(),
                                                f.message(),
                                                f.suggestedFix()))
                        .toList();
        return new LoomGuardSnapshot(
                enabled, tl.totalEstimatedBytes(), tl.distinctLocals(), top, recent);
    }

    /** 测试辅助：构造一个 CRITICAL 发现，避免在测试中硬编码字符串。 */
    static Finding critical(String ruleId, String message) {
        return Finding.builder(Severity.CRITICAL, ruleId).message(message).build();
    }
}
