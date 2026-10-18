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
package io.github.nephets.loomguard.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/** 线程本地变量内存风险审计：基于 {@link ThreadLocalRegistry} 的快照给出保守上界估算。 */
public final class ThreadLocalAudit {

    private static final Logger log = LoggerFactory.getLogger(ThreadLocalAudit.class);

    private final ThreadLocalRegistry registry;

    /** 基于指定注册表审计。 */
    public ThreadLocalAudit(ThreadLocalRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /** 基于进程唯一注册表审计。 */
    public ThreadLocalAudit() {
        this(ThreadLocalRegistry.getInstance());
    }

    /** 生成审计结果。 */
    public ThreadLocalReport audit() {
        List<ThreadLocalEntry> entries = registry.snapshot();
        long total = entries.stream().mapToLong(ThreadLocalEntry::estimatedBytes).sum();
        log.debug("线程本地审计完成：distinctLocals={}, totalEstimatedBytes={}", entries.size(), total);
        return ThreadLocalReport.of(total, entries.size(), entries);
    }
}
