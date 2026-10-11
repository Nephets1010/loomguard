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

import java.time.Instant;
import java.util.List;

/** 一次完整巡检的结果：任务观测 + 误用发现 + 线程本地审计。 */
public record LoomGuardReport(
        TaskObservation observation,
        List<Finding> findings,
        ThreadLocalReport threadLocalReport,
        Instant observedAt) {

    /** 构造结果。 */
    public static LoomGuardReport of(
            TaskObservation observation,
            List<Finding> findings,
            ThreadLocalReport threadLocalReport) {
        return new LoomGuardReport(
                observation, List.copyOf(findings), threadLocalReport, Instant.now());
    }

    /** 本次巡检最严重的级别（无发现时视为 OK）。 */
    public Severity worst() {
        Severity worst = Severity.OK;
        for (Finding f : findings) {
            worst = worst.max(f.severity());
        }
        return worst;
    }

    /** 是否包含 CRITICAL 级发现。 */
    public boolean hasCritical() {
        return findings.stream().anyMatch(f -> f.severity() == Severity.CRITICAL);
    }
}
