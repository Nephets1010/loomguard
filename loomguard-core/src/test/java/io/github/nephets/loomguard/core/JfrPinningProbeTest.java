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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 JFR 探针确实能在运行时真实探测到 pinning（而非静态分析）。 */
class JfrPinningProbeTest {

    private final JfrPinningProbe probe = new JfrPinningProbe();

    @Test
    void synchronizedWithSleepIsPinned() {
        Object lock = new Object();
        PinningReport report =
                probe.probe(
                        () -> {
                            synchronized (lock) {
                                sleep(40);
                            }
                        });
        assertThat(report.anyPinned()).isTrue();
        assertThat(report.pinnedCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void reentrantLockWithSleepIsNotPinned() {
        var lock = new java.util.concurrent.locks.ReentrantLock();
        PinningReport report =
                probe.probe(
                        () -> {
                            lock.lock();
                            try {
                                sleep(40);
                            } finally {
                                lock.unlock();
                            }
                        });
        assertThat(report.anyPinned()).isFalse();
        assertThat(report.pinnedCount()).isEqualTo(0);
    }

    @Test
    void plainBlockingSleepIsNotPinned() {
        // 阻塞 I/O（这里用 sleep 模拟）放在虚拟线程上、不在 synchronized 里 —— 这正是虚拟线程的设计目标
        PinningReport report = probe.probe(() -> sleep(30));
        assertThat(report.anyPinned()).isFalse();
    }

    @Test
    void pinningDetectedThroughHelperMethod() {
        // 证明探针能穿透调用栈，定位到真正持有 monitor 的那个方法（而非测试壳）
        Object lock = new Object();
        PinningReport report = probe.probe(() -> blockingInSync(lock));
        assertThat(report.anyPinned()).isTrue();
        assertThat(report.events().get(0).stackTrace()).anyMatch(f -> f.contains("blockingInSync"));
    }

    private static void blockingInSync(Object lock) {
        synchronized (lock) {
            sleep(20);
        }
    }

    @Test
    void doubleSynchronizedBlockProducesMultiplePinEvents() {
        Object a = new Object();
        Object b = new Object();
        PinningReport report =
                probe.probe(
                        () -> {
                            synchronized (a) {
                                sleep(10);
                            }
                            synchronized (b) {
                                sleep(10);
                            }
                        });
        assertThat(report.anyPinned()).isTrue();
        assertThat(report.pinnedCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void hotFramePointsToOurPackage() {
        Object lock = new Object();
        PinningReport report =
                probe.probe(
                        () -> {
                            synchronized (lock) {
                                sleep(20);
                            }
                        });
        assertThat(report.events()).isNotEmpty();
        assertThat(report.events().get(0).hotFrame()).startsWith("io.github.nephets");
    }

    @Test
    void stackTraceContainsOurMethod() {
        Object lock = new Object();
        PinningReport report =
                probe.probe(
                        () -> {
                            synchronized (lock) {
                                sleep(20);
                            }
                        });
        List<String> stack = report.events().get(0).stackTrace();
        assertThat(stack).anyMatch(frame -> frame.contains("JfrPinningProbeTest"));
    }

    @Test
    void callableReturnsValueAndIsNotPinned() throws Exception {
        PinningReport report = probe.probe(() -> "hello");
        assertThat(report.anyPinned()).isFalse();
    }

    @Test
    void throwingTaskIsReportedAsFailureByLoomProbe() {
        LoomProbe loomProbe = new LoomProbe();
        TaskObservation obs =
                loomProbe.observe(
                        TaskSpec.plain("throws"),
                        () -> {
                            throw new IllegalStateException("boom");
                        });
        assertThat(obs.succeeded()).isFalse();
        assertThat(obs.failureMessage()).isNotNull();
    }

    /**
     * 回归测试：每次探测都会落一个临时 .jfr 文件，必须在返回前回收。
     *
     * <p>修复前这些文件从未被删除，长时间运行会把临时目录撑爆。
     */
    @Test
    void probeCleansUpItsTempRecordingFile() throws IOException {
        long before = countLoomGuardTempFiles();
        probe.probe(() -> sleep(10));
        assertThat(countLoomGuardTempFiles()).isEqualTo(before);
    }

    private static long countLoomGuardTempFiles() throws IOException {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> files = Files.list(tmp)) {
            return files.filter(p -> p.getFileName().toString().startsWith("loomguard"))
                    .filter(p -> p.getFileName().toString().endsWith(".jfr"))
                    .count();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
