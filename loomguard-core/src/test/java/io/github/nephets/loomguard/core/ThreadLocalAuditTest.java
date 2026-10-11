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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 ThreadLocal 内存风险审计：按线程数放大字节数。 */
class ThreadLocalAuditTest {

    private final ThreadLocalRegistry registry = new ThreadLocalRegistry();

    @AfterEach
    void tearDown() {
        registry.reset();
    }

    @Test
    void setInNThreadsAccumulatesSetCallCount() {
        TrackedThreadLocal<String> local =
                new TrackedThreadLocal<>(
                        "user", s -> s == null ? 0 : (long) s.length() * 2, registry);
        int n = 8;
        for (int i = 0; i < n; i++) {
            int idx = i;
            runOnVirtualThread(() -> local.set("user-" + idx)); // 每个字符串 6 字节 * 2 = 12
        }
        ThreadLocalReport report = new ThreadLocalAudit(registry).audit();
        assertThat(report.distinctLocals()).isEqualTo(1);
        ThreadLocalEntry entry = report.entries().get(0);
        assertThat(entry.setCallCount()).isEqualTo(n);
        assertThat(entry.bytesPerThread()).isEqualTo(12);
        assertThat(entry.estimatedBytes()).isEqualTo(12L * n);
        assertThat(report.totalEstimatedBytes()).isEqualTo(12L * n);
    }

    @Test
    void nullValueEstimatesZeroBytes() {
        TrackedThreadLocal<String> local =
                new TrackedThreadLocal<>("maybeNull", s -> s == null ? 0 : 100L, registry);
        runOnVirtualThread(() -> local.set(null));
        ThreadLocalReport report = new ThreadLocalAudit(registry).audit();
        assertThat(report.entries().get(0).bytesPerThread()).isZero();
        assertThat(report.totalEstimatedBytes()).isZero();
    }

    @Test
    void multipleDistinctLocalsOrderedByEstimatedBytesDesc() {
        TrackedThreadLocal<String> big = new TrackedThreadLocal<>("big", s -> 1000L, registry);
        TrackedThreadLocal<String> small = new TrackedThreadLocal<>("small", s -> 10L, registry);
        runOnVirtualThread(() -> small.set("x"));
        for (int i = 0; i < 3; i++) {
            runOnVirtualThread(() -> big.set("y"));
        }
        ThreadLocalReport report = new ThreadLocalAudit(registry).audit();
        assertThat(report.distinctLocals()).isEqualTo(2);
        assertThat(report.entries().get(0).name()).isEqualTo("big");
        assertThat(report.entries().get(0).estimatedBytes()).isEqualTo(3000L);
    }

    @Test
    void resetClearsAll() {
        TrackedThreadLocal<String> local = new TrackedThreadLocal<>("tmp", s -> 5L, registry);
        runOnVirtualThread(() -> local.set("v"));
        assertThat(registry.distinctLocals()).isEqualTo(1);
        registry.reset();
        assertThat(registry.distinctLocals()).isZero();
        assertThat(registry.totalEstimatedBytes()).isZero();
    }

    @Test
    void auditReportReflectsEntryFields() {
        TrackedThreadLocal<String> local = new TrackedThreadLocal<>("fielded", s -> 7L, registry);
        runOnVirtualThread(() -> local.set("abc"));
        ThreadLocalReport report = new ThreadLocalAudit(registry).audit();
        ThreadLocalEntry entry = report.entries().get(0);
        assertThat(entry.name()).isEqualTo("fielded");
        assertThat(entry.bytesPerThread()).isEqualTo(7);
    }

    private static void runOnVirtualThread(Runnable r) {
        try {
            Thread.startVirtualThread(r).join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
