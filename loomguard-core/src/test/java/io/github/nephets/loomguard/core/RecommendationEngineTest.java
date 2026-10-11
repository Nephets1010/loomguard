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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证误用规则引擎：确定性地把观测翻译成发现。 */
class RecommendationEngineTest {

    private final RecommendationEngine engine = new RecommendationEngine();

    private TaskObservation pinned(TaskSpec spec) {
        PinningEvent event =
                new PinningEvent(
                        List.of("io.github.nephets.loomguard.core.Foo#bar"),
                        0L,
                        0,
                        java.time.Instant.now());
        return new TaskObservation(PinningReport.of(List.of(event)), 1_000_000L, spec, true, null);
    }

    private TaskObservation notPinned(TaskSpec spec) {
        return new TaskObservation(PinningReport.empty(), 1_000_000L, spec, true, null);
    }

    @Test
    void pinnedProducesCritical() {
        List<Finding> findings = engine.recommend(pinned(TaskSpec.blockingIo("x")));
        assertThat(findings)
                .anyMatch(
                        f -> f.severity() == Severity.CRITICAL && f.ruleId().equals("LOOM-PINNED"));
    }

    @Test
    void pinnedWithBlockingIoAddsExtraCritical() {
        List<Finding> findings = engine.recommend(pinned(TaskSpec.blockingIo("x")));
        assertThat(findings)
                .anyMatch(
                        f ->
                                f.ruleId().equals("LOOM-PINNED-BLOCKING-IO")
                                        && f.severity() == Severity.CRITICAL);
    }

    @Test
    void pinnedFindingHasSuggestedFixAndEvidence() {
        Finding f =
                engine.recommend(pinned(TaskSpec.blockingIo("x"))).stream()
                        .filter(x -> x.ruleId().equals("LOOM-PINNED"))
                        .findFirst()
                        .orElseThrow();
        assertThat(f.suggestedFix()).isNotBlank();
        assertThat(f.evidenceStack()).isNotEmpty();
    }

    @Test
    void cpuBoundNotPinnedProducesWarn() {
        List<Finding> findings = engine.recommend(notPinned(TaskSpec.cpuBound("x")));
        assertThat(findings)
                .anyMatch(
                        f -> f.severity() == Severity.WARN && f.ruleId().equals("LOOM-CPU-BOUND"));
    }

    @Test
    void blockingIoNotPinnedIsOk() {
        List<Finding> findings = engine.recommend(notPinned(TaskSpec.blockingIo("x")));
        assertThat(findings)
                .anyMatch(f -> f.ruleId().equals("LOOM-OK") && f.severity() == Severity.OK);
        assertThat(findings).noneMatch(f -> f.severity() == Severity.CRITICAL);
    }

    @Test
    void plainNotPinnedIsOk() {
        List<Finding> findings = engine.recommend(notPinned(TaskSpec.plain("x")));
        assertThat(findings).anyMatch(f -> f.ruleId().equals("LOOM-OK"));
    }

    @Test
    void worstIsCriticalWhenPresent() {
        assertThat(engine.worst(engine.recommend(pinned(TaskSpec.blockingIo("x")))))
                .isEqualTo(Severity.CRITICAL);
    }

    @Test
    void worstIsWarnForCpuBound() {
        assertThat(engine.worst(engine.recommend(notPinned(TaskSpec.cpuBound("x")))))
                .isEqualTo(Severity.WARN);
    }

    @Test
    void worstIsOkWhenClean() {
        assertThat(engine.worst(engine.recommend(notPinned(TaskSpec.plain("x")))))
                .isEqualTo(Severity.OK);
    }

    @Test
    void worstPicksMoreSevereAmongMixed() {
        List<Finding> mixed =
                List.of(
                        Finding.builder(Severity.OK, "A").build(),
                        Finding.builder(Severity.CRITICAL, "B").build(),
                        Finding.builder(Severity.WARN, "C").build());
        assertThat(engine.worst(mixed)).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void messageIsNeverBlank() {
        for (TaskSpec spec :
                List.of(TaskSpec.blockingIo("a"), TaskSpec.cpuBound("b"), TaskSpec.plain("c"))) {
            for (Finding f : engine.recommend(notPinned(spec))) {
                assertThat(f.message()).isNotBlank();
            }
        }
    }
}
