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

import java.time.Clock;
import java.util.Objects;

/**
 * 任务观测门面：在虚拟线程上运行任务，记录墙钟耗时并真实探测 pinning，得到 {@link TaskObservation}。
 *
 * <p><strong>为什么耗时用 {@code System.nanoTime()} 而不是注入的 {@link Clock}：</strong>这两者测的是两种根本不同的时间。 {@code
 * nanoTime} 是<strong>单调</strong>时钟，只用于计量「两个时刻之间过去了多久」，它不受系统时钟调整、 NTP 校时、夏令时与手动改表的影响；{@link Clock}
 * 测的是<strong>墙上时间</strong>（现在几点）， 用来给数据打时间戳、判断「多旧」。计量耗时若用墙上时间，一次 NTP 回拨就能让耗时变成负数或一个荒谬的巨大值， 而这类 bug
 * 只在生产环境偶发、无法复现。因此本类保留 {@code nanoTime} 计量耗时， 只把 {@code Clock} 传给下游用于<strong>打时间戳</strong>（{@link
 * PinningEvent#capturedAt()}）。
 */
public final class LoomProbe {

    private static final Logger log = LoggerFactory.getLogger(LoomProbe.class);

    private final JfrPinningProbe pinningProbe;

    /** 使用系统时钟。 */
    public LoomProbe() {
        this(Clock.systemUTC());
    }

    /** 指定时钟（用于给探测到的事件打时间戳）。 */
    public LoomProbe(Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        this.pinningProbe = new JfrPinningProbe(clock);
    }

    /** 观测一个 Runnable 任务。 */
    public TaskObservation observe(TaskSpec spec, Runnable task) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(task, "task must not be null");
        long start = System.nanoTime();
        boolean succeeded = true;
        String failureMessage = null;
        PinningReport report;
        try {
            report = pinningProbe.probe(task);
        } catch (Throwable t) {
            succeeded = false;
            failureMessage = t.getMessage();
            report = PinningReport.empty();
            log.error("JFR pinning 探测失败，按空报告兜底：spec={}", spec, t);
        }
        long wall = System.nanoTime() - start;
        log.info(
                "任务观测完成：spec={}, wallNanos={}, succeeded={}, pinned={}",
                spec,
                wall,
                succeeded,
                report.anyPinned());
        return new TaskObservation(report, wall, spec, succeeded, failureMessage);
    }
}
