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

import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证聚合门面把「探针 → 规则 → 审计」串成一次完整巡检。 */
class LoomGuardTest {

    private final LoomGuard guard = LoomGuard.create();

    @AfterEach
    void tearDown() {
        ThreadLocalRegistry.getInstance().reset();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void badSyncUsageIsCritical() {
        Object lock = new Object();
        LoomGuardReport report =
                guard.inspect(
                        TaskSpec.blockingIo("bad"),
                        () -> {
                            synchronized (lock) {
                                sleep(30);
                            }
                        });
        assertThat(report.hasCritical()).isTrue();
        assertThat(report.findings()).anyMatch(f -> f.ruleId().equals("LOOM-PINNED"));
        assertThat(report.worst()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void goodReentrantLockUsageIsNotCritical() {
        ReentrantLock lock = new ReentrantLock();
        LoomGuardReport report =
                guard.inspect(
                        TaskSpec.blockingIo("good"),
                        () -> {
                            lock.lock();
                            try {
                                sleep(30);
                            } finally {
                                lock.unlock();
                            }
                        });
        assertThat(report.hasCritical()).isFalse();
        assertThat(report.worst()).isEqualTo(Severity.OK);
    }

    @Test
    void cpuBoundUsageWarns() {
        LoomGuardReport report =
                guard.inspect(
                        TaskSpec.cpuBound("cpu"),
                        () -> {
                            double s = 0;
                            for (int i = 0; i < 10_000_000; i++) {
                                s += Math.sqrt(i);
                            }
                        });
        assertThat(report.findings()).anyMatch(f -> f.ruleId().equals("LOOM-CPU-BOUND"));
        assertThat(report.worst()).isEqualTo(Severity.WARN);
    }

    @Test
    void plainUsageIsOk() {
        LoomGuardReport report = guard.inspect(TaskSpec.plain("plain"), () -> sleep(10));
        assertThat(report.worst()).isEqualTo(Severity.OK);
        assertThat(report.hasCritical()).isFalse();
    }

    @Test
    void inspectProducesObservationWithWallTime() {
        LoomGuardReport report = guard.inspect(TaskSpec.plain("t"), () -> sleep(10));
        assertThat(report.observation().wallTimeMillis()).isGreaterThanOrEqualTo(0);
        assertThat(report.observation().succeeded()).isTrue();
    }

    @Test
    void auditThreadLocalsReflectsTrackedLocal() {
        TrackedThreadLocal<String> local =
                new TrackedThreadLocal<>("tenant", s -> s == null ? 0 : 8L);
        runVt(() -> local.set("acme"));
        ThreadLocalReport tl = guard.auditThreadLocals();
        assertThat(tl.distinctLocals()).isEqualTo(1);
        assertThat(tl.totalEstimatedBytes()).isEqualTo(8);
    }

    private static void runVt(Runnable r) {
        try {
            Thread.startVirtualThread(r).join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
