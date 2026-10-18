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
package io.github.nephets.loomguard.starter;

import io.github.nephets.loomguard.core.LoomGuardReport;
import io.github.nephets.loomguard.core.PinningReport;
import io.github.nephets.loomguard.core.TaskObservation;
import io.github.nephets.loomguard.core.TaskSpec;
import io.github.nephets.loomguard.core.ThreadLocalReport;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 钉住时间窗口判定与「淘汰可观测」两条契约。
 *
 * <p>窗口用例全部用<strong>固定时钟</strong>：靠 {@code sleep} 制造时间差既慢又不稳定， 而「恰好等于窗口长度」这种边界根本没法靠 sleep 命中。
 */
class LoomGuardCollectorWindowTest {

    private static final Instant NOW = Instant.parse("2026-09-09T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static LoomGuardReport reportAt(Instant observedAt) {
        return LoomGuardReport.of(
                new TaskObservation(PinningReport.empty(), 1L, TaskSpec.plain("x"), true, null),
                List.of(),
                ThreadLocalReport.of(0L, 0, List.of()),
                observedAt);
    }

    @Test
    void defaultCapacityIsTwenty() {
        assertThat(new LoomGuardCollector().capacity())
                .isEqualTo(LoomGuardCollector.DEFAULT_CAPACITY);
    }

    @Test
    void capacityIsClampedToAtLeastOne() {
        assertThat(new LoomGuardCollector(0).capacity()).isEqualTo(1);
        assertThat(new LoomGuardCollector(-5).capacity()).isEqualTo(1);
    }

    @Test
    void nowComesFromTheInjectedClock() {
        assertThat(new LoomGuardCollector(CLOCK).now()).isEqualTo(NOW);
    }

    @Test
    void recentWithinReturnsOnlyReportsInsideTheWindow() {
        LoomGuardCollector collector = new LoomGuardCollector(10, CLOCK);
        collector.record(reportAt(NOW.minusSeconds(60)));
        collector.record(reportAt(NOW.minusSeconds(600)));
        assertThat(collector.recent()).hasSize(2);
        assertThat(collector.recentWithin(Duration.ofMinutes(5))).hasSize(1);
    }

    /** 边界：恰好等于窗口长度 —— 闭区间，算窗口内。 */
    @Test
    void reportExactlyAtTheWindowEdgeIsIncluded() {
        LoomGuardCollector collector = new LoomGuardCollector(10, CLOCK);
        collector.record(reportAt(NOW.minus(Duration.ofMinutes(5))));
        assertThat(collector.recentWithin(Duration.ofMinutes(5))).hasSize(1);
    }

    /** 边界：差一纳秒 —— 落在窗口外。 */
    @Test
    void reportOneNanoOutsideTheWindowIsExcluded() {
        LoomGuardCollector collector = new LoomGuardCollector(10, CLOCK);
        collector.record(reportAt(NOW.minus(Duration.ofMinutes(5)).minusNanos(1)));
        assertThat(collector.recentWithin(Duration.ofMinutes(5))).isEmpty();
    }

    @Test
    void zeroWindowKeepsOnlyWhatHappenedAtThisInstant() {
        LoomGuardCollector collector = new LoomGuardCollector(10, CLOCK);
        collector.record(reportAt(NOW));
        collector.record(reportAt(NOW.minusNanos(1)));
        assertThat(collector.recentWithin(Duration.ZERO)).hasSize(1);
    }

    @Test
    void emptyCollectorHasNoWindowedResults() {
        assertThat(new LoomGuardCollector(10, CLOCK).recentWithin(Duration.ofHours(1))).isEmpty();
    }

    @Test
    void windowIsRequired() {
        assertThatThrownBy(() -> new LoomGuardCollector(CLOCK).recentWithin(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void clockIsRequired() {
        assertThatThrownBy(() -> new LoomGuardCollector(10, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LoomGuardCollector((Clock) null))
                .isInstanceOf(NullPointerException.class);
    }

    /** 淘汰必须可计数：否则「只剩这几条」到底是「只发生了这几条」还是「旧的被扔了」无从判断。 */
    @Test
    void evictionIsCounted() {
        LoomGuardCollector collector = new LoomGuardCollector(2, CLOCK);
        collector.record(reportAt(NOW));
        collector.record(reportAt(NOW));
        assertThat(collector.evictedCount()).isZero();
        collector.record(reportAt(NOW));
        collector.record(reportAt(NOW));
        assertThat(collector.evictedCount()).isEqualTo(2);
        assertThat(collector.recent()).hasSize(2);
    }

    @Test
    void latestIsTheMostRecentByInsertionOrder() {
        LoomGuardCollector collector = new LoomGuardCollector(5, CLOCK);
        LoomGuardReport first = reportAt(NOW.minusSeconds(10));
        collector.record(first);
        collector.record(reportAt(NOW));
        assertThat(collector.latest()).isNotSameAs(first);
        assertThat(collector.latest().observedAt()).isEqualTo(NOW);
    }
}
