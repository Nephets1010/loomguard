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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 钉住「阈值如何改变判定结论」：重点是边界（恰好达标 / 差一点 / 极大值 / 0）。 */
class RecommendationEngineThresholdTest {

    private static final Instant NOW = Instant.parse("2026-05-06T07:08:09Z");

    private final RecommendationEngine engine = new RecommendationEngine();

    private static PinningEvent pinned(long durationNanos, int index) {
        return new PinningEvent(List.of("io.github.nephets.Foo#bar"), durationNanos, index, NOW);
    }

    private static TaskObservation observation(int pinnedCount, long durationNanos) {
        List<PinningEvent> events = new java.util.ArrayList<>();
        for (int i = 0; i < pinnedCount; i++) {
            events.add(pinned(durationNanos, i));
        }
        return new TaskObservation(
                PinningReport.of(events), 1_000_000L, TaskSpec.blockingIo("x"), true, null);
    }

    private static Severity severityOf(List<Finding> findings) {
        return findings.stream()
                .filter(f -> f.ruleId().equals(RecommendationEngine.RULE_PINNED))
                .findFirst()
                .orElseThrow()
                .severity();
    }

    @Test
    void defaultThresholdMatchesTheNamedConstant() {
        assertThat(RecommendationEngine.DEFAULT_THRESHOLD).isEqualTo(PinningThreshold.DEFAULT);
    }

    @Test
    void singleOverloadIsEquivalentToDefaultThreshold() {
        TaskObservation obs = observation(1, 0L);
        assertThat(engine.recommend(obs))
                .isEqualTo(engine.recommend(obs, PinningThreshold.DEFAULT));
    }

    @Test
    void onePinIsCriticalUnderTheDefaultThreshold() {
        assertThat(severityOf(engine.recommend(observation(1, 0L)))).isEqualTo(Severity.CRITICAL);
    }

    /** 边界：恰好达到次数阈值 —— 闭区间，达标即命中。 */
    @Test
    void countExactlyAtTheThresholdIsCritical() {
        assertThat(severityOf(engine.recommend(observation(2, 0L), PinningThreshold.of(2, 0L))))
                .isEqualTo(Severity.CRITICAL);
    }

    /** 边界：差一个 —— 不能因为「差不多」就升级。 */
    @Test
    void countOneBelowTheThresholdDegradesToWarn() {
        assertThat(severityOf(engine.recommend(observation(1, 0L), PinningThreshold.of(2, 0L))))
                .isEqualTo(Severity.WARN);
    }

    /** 边界：恰好达到时长阈值。 */
    @Test
    void durationExactlyAtTheThresholdIsCritical() {
        assertThat(
                        severityOf(
                                engine.recommend(
                                        observation(1, 5_000L), PinningThreshold.of(1, 5_000L))))
                .isEqualTo(Severity.CRITICAL);
    }

    @Test
    void durationOneNanoBelowTheThresholdDegradesToWarn() {
        assertThat(
                        severityOf(
                                engine.recommend(
                                        observation(1, 4_999L), PinningThreshold.of(1, 5_000L))))
                .isEqualTo(Severity.WARN);
    }

    /** 极大值：阈值大到不可能达到时，判定必须稳定降级，而不是溢出成「命中」。 */
    @Test
    void unreachableDurationThresholdNeverEscalates() {
        assertThat(
                        severityOf(
                                engine.recommend(
                                        observation(3, 1_000L),
                                        PinningThreshold.of(1, Long.MAX_VALUE))))
                .isEqualTo(Severity.WARN);
        assertThat(
                        severityOf(
                                engine.recommend(
                                        observation(3, 1_000L),
                                        PinningThreshold.of(Integer.MAX_VALUE, 0L))))
                .isEqualTo(Severity.WARN);
    }

    /** 时长取的是「最久的那一次」，不是求和也不是平均。 */
    @Test
    void longestPinDrivesTheDurationDimension() {
        List<PinningEvent> events = List.of(pinned(10L, 0), pinned(1_000L, 1), pinned(20L, 2));
        TaskObservation obs =
                new TaskObservation(
                        PinningReport.of(events), 1L, TaskSpec.blockingIo("x"), true, null);
        assertThat(severityOf(engine.recommend(obs, PinningThreshold.of(1, 1_000L))))
                .isEqualTo(Severity.CRITICAL);
        assertThat(severityOf(engine.recommend(obs, PinningThreshold.of(1, 1_001L))))
                .isEqualTo(Severity.WARN);
    }

    /** 阈值不达时仍要保留「发生了 pinning」这一信息：降级到 WARN，而不是消失。 */
    @Test
    void belowThresholdStillReportsThatPinningHappened() {
        List<Finding> findings = engine.recommend(observation(1, 0L), PinningThreshold.of(5, 0L));
        assertThat(findings).anyMatch(f -> f.ruleId().equals(RecommendationEngine.RULE_PINNED));
        assertThat(severityOf(findings)).isEqualTo(Severity.WARN);
    }

    /** 阻塞 I/O + pinning 是最严重的形态，不受阈值降级影响。 */
    @Test
    void blockingIoInsidePinnedSectionStaysCriticalRegardlessOfThreshold() {
        List<Finding> findings = engine.recommend(observation(1, 0L), PinningThreshold.of(99, 0L));
        assertThat(findings)
                .anyMatch(
                        f ->
                                f.ruleId().equals(RecommendationEngine.RULE_PINNED_BLOCKING_IO)
                                        && f.severity() == Severity.CRITICAL);
    }

    @Test
    void nonPinnedObservationsIgnoreTheThreshold() {
        TaskObservation clean =
                new TaskObservation(PinningReport.empty(), 1L, TaskSpec.plain("x"), true, null);
        assertThat(engine.recommend(clean, PinningThreshold.of(1, 0L)))
                .isEqualTo(engine.recommend(clean, PinningThreshold.of(9, 9L)));
    }

    @Test
    void thresholdAndObservationAreBothRequired() {
        assertThatThrownBy(() -> engine.recommend(null, PinningThreshold.DEFAULT))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> engine.recommend(observation(1, 0L), null))
                .isInstanceOf(NullPointerException.class);
    }
}
