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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** LoomGuardReport 聚合语义。 */
class LoomGuardReportTest {

    private TaskObservation obs() {
        return new TaskObservation(
                PinningReport.empty(), 1_000_000L, TaskSpec.plain("x"), true, null);
    }

    @Test
    void worstWithCritical() {
        LoomGuardReport report =
                LoomGuardReport.of(
                        obs(),
                        List.of(Finding.builder(Severity.CRITICAL, "LOOM-PINNED").build()),
                        ThreadLocalReport.of(0, 0, List.of()));
        assertThat(report.worst()).isEqualTo(Severity.CRITICAL);
        assertThat(report.hasCritical()).isTrue();
    }

    @Test
    void worstWithOnlyOk() {
        LoomGuardReport report =
                LoomGuardReport.of(
                        obs(),
                        List.of(Finding.builder(Severity.OK, "LOOM-OK").build()),
                        ThreadLocalReport.of(0, 0, List.of()));
        assertThat(report.worst()).isEqualTo(Severity.OK);
        assertThat(report.hasCritical()).isFalse();
    }

    @Test
    void observedAtIsSet() {
        LoomGuardReport report =
                LoomGuardReport.of(obs(), List.of(), ThreadLocalReport.of(0, 0, List.of()));
        assertThat(report.observedAt()).isNotNull();
        assertThat(report.observedAt()).isBeforeOrEqualTo(Instant.now());
    }
}
