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
package io.github.nephets.loomguard.sample;

import io.github.nephets.loomguard.core.LoomGuard;
import io.github.nephets.loomguard.core.LoomGuardReport;
import io.github.nephets.loomguard.core.TaskSpec;
import io.github.nephets.loomguard.starter.LoomGuardCollector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 对照端点：
 *
 * <ul>
 *   <li>{@code /demo/bad} —— 在 synchronized 临界区里阻塞：会被探针真实探测到 pinning（CRITICAL）。
 *   <li>{@code /demo/good} —— 用 ReentrantLock 替代 synchronized：不会 pinning。
 *   <li>{@code /demo/cpu} —— CPU 密集任务放在虚拟线程：触发 WARN（应挪到平台线程池）。
 *   <li>{@code /demo/plain} —— 普通阻塞 I/O：不 pinning，正是虚拟线程的设计目标。
 * </ul>
 */
@RestController
@RequestMapping("/demo")
public class ProbeController {

    private static final Logger log = LoggerFactory.getLogger(ProbeController.class);

    private final LoomGuard loomGuard;
    private final LoomGuardCollector collector;

    public ProbeController(LoomGuard loomGuard, LoomGuardCollector collector) {
        this.loomGuard = loomGuard;
        this.collector = collector;
    }

    /** 错误用法：synchronized 临界区内阻塞。 */
    @GetMapping("/bad")
    public LoomGuardReport bad() {
        log.info("收到 /demo/bad：synchronized 临界区内阻塞（预期触发 pinning CRITICAL）");
        Object lock = new Object();
        return run(
                TaskSpec.blockingIo("synchronized-blocking-io"),
                () -> {
                    synchronized (lock) {
                        sleepQuietly(50);
                    }
                });
    }

    /** 正确用法：ReentrantLock 替代 synchronized。 */
    @GetMapping("/good")
    public LoomGuardReport good() {
        log.info("收到 /demo/good：ReentrantLock 替代 synchronized（预期不 pinning）");
        ReentrantLock lock = new ReentrantLock();
        return run(
                TaskSpec.blockingIo("reentrantlock-blocking-io"),
                () -> {
                    lock.lock();
                    try {
                        sleepQuietly(50);
                    } finally {
                        lock.unlock();
                    }
                });
    }

    /** 误用：CPU 密集任务放在虚拟线程上。 */
    @GetMapping("/cpu")
    public LoomGuardReport cpu() {
        log.info("收到 /demo/cpu：CPU 密集任务放在虚拟线程（预期触发 WARN）");
        return run(
                TaskSpec.cpuBound("cpu-bound-loop"),
                () -> {
                    double sum = 0;
                    for (int i = 0; i < 50_000_000; i++) {
                        sum += Math.sqrt(i);
                    }
                });
    }

    /** 正常用法：虚拟线程上做阻塞 I/O，不 pinning。 */
    @GetMapping("/plain")
    public LoomGuardReport plain() {
        log.info("收到 /demo/plain：虚拟线程上阻塞 I/O（预期不 pinning，正常设计目标）");
        return run(TaskSpec.plain("plain-blocking-io"), () -> sleepQuietly(20));
    }

    private LoomGuardReport run(TaskSpec spec, Runnable task) {
        LoomGuardReport report = loomGuard.inspect(spec, task);
        collector.record(report);
        return report;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
