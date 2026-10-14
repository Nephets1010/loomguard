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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import jdk.jfr.EventSettings;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

/**
 * 基于 JFR 的 pinning 探针：在进程内录制 {@code jdk.VirtualThreadPinned} 事件，还原出每次 pinning 的发生栈（hot
 * stack），供调用方定位「哪里被钉住了」。
 *
 * <p>不需要任何特殊 JVM 参数：OpenJDK 21 的 JFR 默认可用，只要录制期间该事件被启用且带堆栈， pinning 一发生就会被捕获。这是运行时真实探测，而非静态扫描。
 */
public final class JfrPinningProbe {

    private static final Logger log = LoggerFactory.getLogger(JfrPinningProbe.class);

    /** 被探测的 JFR 事件名。 */
    public static final String PINNED_EVENT_NAME = "jdk.VirtualThreadPinned";

    /** 在虚拟线程上执行任务并探测 pinning。 */
    public PinningReport probe(Runnable task) {
        log.debug("开始 JFR pinning 探测（Runnable）");
        return probeWrapped(
                () -> {
                    AtomicReference<Throwable> error = new AtomicReference<>();
                    Runnable guarded =
                            () -> {
                                try {
                                    task.run();
                                } catch (Throwable t) {
                                    error.set(t);
                                }
                            };
                    try {
                        Thread.startVirtualThread(guarded).join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("被探测的虚拟线程被中断", e);
                    }
                    if (error.get() != null) {
                        throw new RuntimeException("被探测任务抛出异常", error.get());
                    }
                });
    }

    /** 在虚拟线程上执行有返回值的任务并探测 pinning；异常会照常向上传播。 */
    public <T> PinningReport probe(Callable<T> task) throws Exception {
        log.debug("开始 JFR pinning 探测（Callable）");
        return probeWrapped(
                () -> {
                    AtomicReference<Throwable> error = new AtomicReference<>();
                    Callable<T> guarded =
                            () -> {
                                try {
                                    return task.call();
                                } catch (Throwable t) {
                                    error.set(t);
                                    return null;
                                }
                            };
                    try {
                        Thread.startVirtualThread(
                                        () -> {
                                            try {
                                                guarded.call();
                                            } catch (Exception e) {
                                                // guarded 内部已吞掉任务自身的 Throwable，这里只可能是
                                                // Callable 包装层的意外失败；必须留下日志再交给调用方，
                                                // 否则异常会被 AtomicReference 静默带走。
                                                log.error("被探测任务在虚拟线程执行期间抛出未预期异常", e);
                                                error.set(e);
                                            }
                                        })
                                .join();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("被探测的虚拟线程被中断", e);
                    }
                    if (error.get() != null) {
                        throw new RuntimeException("被探测任务抛出异常", error.get());
                    }
                });
    }

    private PinningReport probeWrapped(ThrowingRunnable runnable) {
        Path dump = tempFile();
        try {
            return recordAndParse(runnable, dump);
        } finally {
            // 每次探测都会落一个临时 .jfr 文件；解析完必须回收，
            // 否则长跑进程会把临时目录撑爆（此前从未删除过）。
            deleteDumpQuietly(dump);
        }
    }

    private PinningReport recordAndParse(ThrowingRunnable runnable, Path dump) {
        // Recording 是 Closeable：用 try-with-resources 保证无论探测任务是否抛异常都释放录制资源
        try (Recording recording = new Recording()) {
            EventSettings settings = recording.enable(PINNED_EVENT_NAME);
            settings.withStackTrace();
            // 0 ms 阈值：捕获所有 pinning，不漏报
            settings.withThreshold(java.time.Duration.ZERO);
            recording.start();
            try {
                runnable.run();
            } finally {
                recording.stop();
                try {
                    recording.dump(dump);
                } catch (IOException e) {
                    log.error("JFR 录制转储失败，按空报告兜底：dump={}", dump, e);
                    return PinningReport.empty();
                }
            }
        }

        List<PinningEvent> events = new ArrayList<>();
        try {
            for (RecordedEvent event : RecordingFile.readAllEvents(dump)) {
                if (!PINNED_EVENT_NAME.equals(event.getEventType().getName())) {
                    continue;
                }
                events.add(toPinningEvent(event, events.size()));
            }
        } catch (IOException e) {
            log.error("读取 JFR 录制事件失败，按空报告兜底：dump={}", dump, e);
            return PinningReport.empty();
        }
        log.debug("JFR pinning 探测完成：捕获 {} 个 pinning 事件", events.size());
        return PinningReport.of(events);
    }

    private static void deleteDumpQuietly(Path dump) {
        try {
            Files.deleteIfExists(dump);
        } catch (IOException e) {
            // 降级点：删除失败不影响本次探测结论，仅记录，剩余文件交由操作系统临时目录策略回收
            log.warn("JFR 临时录制文件删除失败，交由系统临时目录策略回收：dump={}", dump, e);
        }
    }

    private static PinningEvent toPinningEvent(RecordedEvent event, int index) {
        List<String> stack = new ArrayList<>();
        RecordedStackTrace trace = event.getStackTrace();
        if (trace != null) {
            for (RecordedFrame frame : trace.getFrames()) {
                RecordedMethod method = frame.getMethod();
                if (method == null) {
                    continue;
                }
                String line = method.getType().getName() + "#" + method.getName();
                if (frame.getLineNumber() > 0) {
                    line += ":" + frame.getLineNumber();
                }
                stack.add(line);
            }
        }
        return new PinningEvent(List.copyOf(stack), 0L, index, java.time.Instant.now());
    }

    private static Path tempFile() {
        try {
            return Files.createTempFile("loomguard", ".jfr");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 JFR 临时文件", e);
        }
    }

    /** 探针任务包装。 */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    /** 简单的原子引用（避免引入额外依赖）。 */
}
