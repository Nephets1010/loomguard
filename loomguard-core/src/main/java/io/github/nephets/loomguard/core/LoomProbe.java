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

import java.util.Objects;

/** 任务观测门面：在虚拟线程上运行任务，记录墙钟耗时并真实探测 pinning，得到 {@link TaskObservation}。 */
public final class LoomProbe {

    private static final Logger log = LoggerFactory.getLogger(LoomProbe.class);

    private final JfrPinningProbe pinningProbe = new JfrPinningProbe();

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
