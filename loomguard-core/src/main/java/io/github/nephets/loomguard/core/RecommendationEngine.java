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

import java.util.ArrayList;
import java.util.List;

/**
 * 误用规则引擎：把一次观测（是否 pinning + 任务元数据）翻译成一组可执行的发现。
 *
 * <p>纯函数、无副作用、完全确定性：同样的输入永远给出同样的发现。pinning 是否发生由 {@link JfrPinningProbe} 在运行时真实探测，CPU/阻塞的判定来自调用方对
 * {@link TaskSpec} 的声明。
 */
public final class RecommendationEngine {

    public static final String RULE_PINNED = "LOOM-PINNED";
    public static final String RULE_PINNED_BLOCKING_IO = "LOOM-PINNED-BLOCKING-IO";
    public static final String RULE_CPU_BOUND = "LOOM-CPU-BOUND";
    public static final String RULE_OK = "LOOM-OK";

    /** 根据观测结果给出发现列表。 */
    public List<Finding> recommend(TaskObservation obs) {
        List<Finding> findings = new ArrayList<>();
        PinningReport pinning = obs.pinning();
        TaskSpec spec = obs.spec();

        if (pinning.anyPinned()) {
            List<String> hot =
                    pinning.events().isEmpty() ? List.of() : pinning.events().get(0).stackTrace();
            findings.add(
                    Finding.builder(Severity.CRITICAL, RULE_PINNED)
                            .message("虚拟线程被钉住（pinned）：synchronized 临界区内出现阻塞/原生调用，载体线程被独占，吞吐骤降。")
                            .suggestedFix(
                                    "用 java.util.concurrent.locks.ReentrantLock 替代 synchronized；或把阻塞调用移到"
                                            + " synchronized 临界区之外。")
                            .evidenceStack(hot)
                            .build());
            if (spec.blockingIo()) {
                findings.add(
                        Finding.builder(Severity.CRITICAL, RULE_PINNED_BLOCKING_IO)
                                .message(
                                        "在 synchronized 临界区内做阻塞 I/O —— 这是最严重的 pinning 形态，会彻底堵死载体线程。")
                                .suggestedFix(
                                        "把阻塞 I/O（网络/文件/数据库）移出 synchronized 块，或改用 ReentrantLock 并对临界区保持极短。")
                                .build());
            }
            return findings;
        }

        if (spec.cpuBound()) {
            findings.add(
                    Finding.builder(Severity.WARN, RULE_CPU_BOUND)
                            .message("CPU 密集任务运行在虚拟线程上：会长期占用载体线程，挤占同一 carrier 上的其他虚拟线程。")
                            .suggestedFix("改用 ForkJoinPool.commonPool() 或独立的平台线程池执行 CPU 密集任务。")
                            .build());
            return findings;
        }

        if (spec.blockingIo()) {
            findings.add(
                    Finding.builder(Severity.OK, RULE_OK)
                            .message("阻塞 I/O 在虚拟线程上运行且未 pinning —— 这正是虚拟线程的设计目标，没有问题。")
                            .build());
            return findings;
        }

        findings.add(
                Finding.builder(Severity.OK, RULE_OK)
                        .message("任务在虚拟线程上运行良好：无 pinning、无已知误用。")
                        .build());
        return findings;
    }

    /** 一组发现里最严重的级别。 */
    public Severity worst(List<Finding> findings) {
        Severity worst = Severity.OK;
        for (Finding f : findings) {
            worst = worst.max(f.severity());
        }
        return worst;
    }
}
