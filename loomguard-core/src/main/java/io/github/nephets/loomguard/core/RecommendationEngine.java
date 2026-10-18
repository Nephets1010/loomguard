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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 误用规则引擎：把一次观测（是否 pinning + 任务元数据）翻译成一组可执行的发现。
 *
 * <p>纯函数、无副作用、完全确定性：同样的输入永远给出同样的发现。pinning 是否发生由 {@link JfrPinningProbe} 在运行时真实探测，CPU/阻塞的判定来自调用方对
 * {@link TaskSpec} 的声明。
 */
public final class RecommendationEngine {

    private static final Logger log = LoggerFactory.getLogger(RecommendationEngine.class);

    public static final String RULE_PINNED = "LOOM-PINNED";
    public static final String RULE_PINNED_BLOCKING_IO = "LOOM-PINNED-BLOCKING-IO";
    public static final String RULE_CPU_BOUND = "LOOM-CPU-BOUND";
    public static final String RULE_OK = "LOOM-OK";

    /** 默认判定阈值：见 {@link PinningThreshold#DEFAULT} 关于「为什么是次数 ≥ 1、时长 ≥ 0」的论证。 */
    public static final PinningThreshold DEFAULT_THRESHOLD = PinningThreshold.DEFAULT;

    /** 根据观测结果给出发现列表（使用默认阈值）。 */
    public List<Finding> recommend(TaskObservation obs) {
        return recommend(obs, DEFAULT_THRESHOLD);
    }

    /**
     * 按给定阈值给出发现列表。
     *
     * <p><strong>为什么阈值要作为参数进来，而不是引擎内部的常量：</strong>「多少次算严重」是<strong>业务口径</strong>而非技术事实。 一个 QPS
     * 百万的网关被钉一次也是大事，一个每天跑一次的批处理任务被钉十次可能不值得半夜叫人。
     * 把阈值留在引擎内部当作常量，等于替所有使用方做了这个决定；把它作为参数，同一个引擎才能服务于不同严格程度的场景。
     */
    public List<Finding> recommend(TaskObservation obs, PinningThreshold threshold) {
        Objects.requireNonNull(obs, "obs must not be null");
        Objects.requireNonNull(threshold, "threshold must not be null");
        log.debug(
                "规则引擎评估观测：pinned={}, spec={}, threshold={}",
                obs.pinning().anyPinned(),
                obs.spec(),
                threshold);
        List<Finding> findings = new ArrayList<>();
        PinningReport pinning = obs.pinning();
        TaskSpec spec = obs.spec();

        if (pinning.anyPinned()) {
            Severity severity = threshold.severityFor(pinning.pinnedCount(), longestPin(pinning));
            log.info(
                    "规则命中：虚拟线程被钉住（pinned），按阈值判定为 {}；pinnedCount={}, blockingIo={}",
                    severity,
                    pinning.pinnedCount(),
                    spec.blockingIo());
            List<String> hot =
                    pinning.events().isEmpty() ? List.of() : pinning.events().get(0).stackTrace();
            findings.add(
                    Finding.builder(severity, RULE_PINNED)
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
            log.info("规则命中：CPU 密集任务跑在虚拟线程上，将产生 WARN 发现");
            findings.add(
                    Finding.builder(Severity.WARN, RULE_CPU_BOUND)
                            .message("CPU 密集任务运行在虚拟线程上：会长期占用载体线程，挤占同一 carrier 上的其他虚拟线程。")
                            .suggestedFix("改用 ForkJoinPool.commonPool() 或独立的平台线程池执行 CPU 密集任务。")
                            .build());
            return findings;
        }

        if (spec.blockingIo()) {
            log.debug("规则命中：阻塞 I/O 且未 pinning —— 符合虚拟线程设计目标，判定 OK");
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
        log.debug("规则引擎产出 {} 条发现", findings.size());
        return findings;
    }

    /**
     * 一份报告里「被钉得最久」的那一次的时长。
     *
     * <p>为什么取最大值而不是求和或平均：阈值问的是「存不存在一次足够严重的 pinning」， 这是一个存在性判断；取平均会让「一百次极短的钉」和「一次致命的长钉」看起来一样，
     * 取求和则会把次数维度偷偷混进时长维度，与阈值里「次数 / 时长两维各自独立」的设计冲突。
     */
    private static long longestPin(PinningReport pinning) {
        long longest = 0L;
        for (PinningEvent event : pinning.events()) {
            longest = Math.max(longest, event.durationNanos());
        }
        return longest;
    }

    /** 一组发现里最严重的级别。 */
    public Severity worst(List<Finding> findings) {
        Objects.requireNonNull(findings, "findings must not be null");
        Severity worst = Severity.OK;
        for (Finding f : findings) {
            worst = worst.max(f.severity());
        }
        return worst;
    }
}
