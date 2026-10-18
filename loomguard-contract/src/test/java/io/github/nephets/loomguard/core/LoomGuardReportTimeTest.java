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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 钉住报告的「时刻」与「最严重级别」契约。 */
class LoomGuardReportTimeTest {

    private static final Instant FROZEN = Instant.parse("2026-07-08T09:10:11Z");

    private static TaskObservation observation() {
        return new TaskObservation(
                PinningReport.empty(), 1_500_000L, TaskSpec.plain("x"), true, null);
    }

    private static ThreadLocalReport threadLocals() {
        return ThreadLocalReport.of(0L, 0, List.of());
    }

    @Test
    void explicitObservedAtWins() {
        LoomGuardReport report =
                LoomGuardReport.of(observation(), List.of(), threadLocals(), FROZEN);
        assertThat(report.observedAt()).isEqualTo(FROZEN);
    }

    @Test
    void explicitConstructorRejectsNullObservedAt() {
        assertThatThrownBy(() -> LoomGuardReport.of(observation(), List.of(), threadLocals(), null))
                .isInstanceOf(NullPointerException.class);
    }

    /** 缺省重载仍然要给出非空时刻：调用方不该被迫处理「没有时刻」的报告。 */
    @Test
    void defaultingConstructorStillStampsANonNullInstant() {
        assertThat(LoomGuardReport.of(observation(), List.of(), threadLocals()).observedAt())
                .isNotNull();
    }

    @Test
    void noFindingsMeansOk() {
        LoomGuardReport report =
                LoomGuardReport.of(observation(), List.of(), threadLocals(), FROZEN);
        assertThat(report.worst()).isEqualTo(Severity.OK);
        assertThat(report.hasCritical()).isFalse();
    }

    @Test
    void criticalIsDetectedAmongMixedFindings() {
        LoomGuardReport report =
                LoomGuardReport.of(
                        observation(),
                        List.of(
                                Finding.builder(Severity.OK, "A").build(),
                                Finding.builder(Severity.CRITICAL, "B").build()),
                        threadLocals(),
                        FROZEN);
        assertThat(report.hasCritical()).isTrue();
        assertThat(report.worst()).isEqualTo(Severity.CRITICAL);
    }

    /** 只有 WARN 时 worst 是 WARN，但 hasCritical 必须为 false —— 两者不是同一件事。 */
    @Test
    void warnIsNotCritical() {
        LoomGuardReport report =
                LoomGuardReport.of(
                        observation(),
                        List.of(Finding.builder(Severity.WARN, "A").build()),
                        threadLocals(),
                        FROZEN);
        assertThat(report.worst()).isEqualTo(Severity.WARN);
        assertThat(report.hasCritical()).isFalse();
    }

    /** 发现列表是防御性拷贝：报告一旦构造，结论就不该再被外部改动。 */
    @Test
    void findingsAreCopiedDefensively() {
        List<Finding> findings = new ArrayList<>();
        findings.add(Finding.builder(Severity.OK, "A").build());
        LoomGuardReport report =
                LoomGuardReport.of(observation(), findings, threadLocals(), FROZEN);
        findings.clear();
        assertThat(report.findings()).hasSize(1);
        assertThatThrownBy(() -> report.findings().add(Finding.builder(Severity.OK, "B").build()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void allComponentsAreRequired() {
        assertThatThrownBy(() -> LoomGuardReport.of(null, List.of(), threadLocals(), FROZEN))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LoomGuardReport.of(observation(), null, threadLocals(), FROZEN))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LoomGuardReport.of(observation(), List.of(), null, FROZEN))
                .isInstanceOf(NullPointerException.class);
    }
}
