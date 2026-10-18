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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 钉住判定阈值的边界：阈值是「判定口径」，口径的每一条边界都必须有测试， 否则「多一次算严重、少一次不算」这条线会在重构中悄悄移动。 */
class PinningThresholdTest {

    @Test
    void defaultThresholdIsOneOccurrenceWithNoDurationFloor() {
        assertThat(PinningThreshold.DEFAULT.minPinnedCount()).isEqualTo(1);
        assertThat(PinningThreshold.DEFAULT.minDurationNanos()).isZero();
    }

    @Test
    void zeroDurationFloorMeansTheDurationDimensionIsOff() {
        assertThat(PinningThreshold.of(1, 0L).isCritical(1, 0L)).isTrue();
    }

    @Test
    void zeroCountThresholdMatchesEvenNothingHappening() {
        assertThat(PinningThreshold.of(0, 0L).matchesNothing()).isTrue();
        assertThat(PinningThreshold.of(1, 0L).matchesNothing()).isFalse();
    }

    /** 边界：恰好等于次数阈值 —— 闭区间。 */
    @Test
    void countExactlyAtThresholdMatches() {
        assertThat(PinningThreshold.of(3, 0L).isCritical(3, 0L)).isTrue();
    }

    @Test
    void countOneBelowThresholdDoesNotMatch() {
        assertThat(PinningThreshold.of(3, 0L).isCritical(2, 0L)).isFalse();
    }

    @Test
    void countOneAboveThresholdMatches() {
        assertThat(PinningThreshold.of(3, 0L).isCritical(4, 0L)).isTrue();
    }

    /** 边界：恰好等于时长阈值。 */
    @Test
    void durationExactlyAtThresholdMatches() {
        assertThat(PinningThreshold.of(1, 1_000L).isCritical(1, 1_000L)).isTrue();
    }

    @Test
    void durationOneNanoBelowThresholdDoesNotMatch() {
        assertThat(PinningThreshold.of(1, 1_000L).isCritical(1, 999L)).isFalse();
    }

    /** 两个维度是与关系：只满足一个不算命中。 */
    @Test
    void bothDimensionsMustBeSatisfied() {
        PinningThreshold threshold = PinningThreshold.of(2, 500L);
        assertThat(threshold.isCritical(2, 499L)).isFalse();
        assertThat(threshold.isCritical(1, 500L)).isFalse();
        assertThat(threshold.isCritical(2, 500L)).isTrue();
    }

    /** 极大值：阈值大到不可达时判定必须稳定为「不命中」，不能溢出成命中。 */
    @Test
    void unreachableThresholdsNeverMatch() {
        assertThat(PinningThreshold.of(Integer.MAX_VALUE, 0L).isCritical(Integer.MAX_VALUE - 1, 0L))
                .isFalse();
        assertThat(PinningThreshold.of(1, Long.MAX_VALUE).isCritical(1, Long.MAX_VALUE - 1))
                .isFalse();
    }

    @Test
    void extremeValuesAtTheCeilingStillWork() {
        assertThat(PinningThreshold.of(Integer.MAX_VALUE, 0L).isCritical(Integer.MAX_VALUE, 0L))
                .isTrue();
        assertThat(PinningThreshold.of(1, Long.MAX_VALUE).isCritical(1, Long.MAX_VALUE)).isTrue();
    }

    @Test
    void nothingHappenedIsAlwaysOk() {
        assertThat(PinningThreshold.DEFAULT.severityFor(0, 0L)).isEqualTo(Severity.OK);
        assertThat(PinningThreshold.of(0, 0L).severityFor(0, 0L)).isEqualTo(Severity.OK);
    }

    @Test
    void belowThresholdDegradesToWarnRatherThanDisappearing() {
        assertThat(PinningThreshold.of(5, 0L).severityFor(1, 0L)).isEqualTo(Severity.WARN);
    }

    @Test
    void atThresholdEscalatesToCritical() {
        assertThat(PinningThreshold.of(5, 0L).severityFor(5, 0L)).isEqualTo(Severity.CRITICAL);
    }

    /** 负数次数：判不出来是不是笔误，但一定是错的，构造期就拒绝。 */
    @Test
    void negativeCountIsRejectedAtConstruction() {
        assertThatThrownBy(() -> PinningThreshold.of(-1, 0L))
                .isInstanceOf(LoomGuardException.class)
                .hasMessageContaining("minPinnedCount");
    }

    @Test
    void negativeDurationIsRejectedAtConstruction() {
        assertThatThrownBy(() -> PinningThreshold.of(1, -1L))
                .isInstanceOf(LoomGuardException.class)
                .hasMessageContaining("minDurationNanos");
    }

    @Test
    void equalThresholdsAreEqualAndHashTheSame() {
        PinningThreshold a = PinningThreshold.of(2, 300L);
        PinningThreshold b = PinningThreshold.of(2, 300L);
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void thresholdsDifferOnEitherDimension() {
        assertThat(PinningThreshold.of(2, 300L)).isNotEqualTo(PinningThreshold.of(3, 300L));
        assertThat(PinningThreshold.of(2, 300L)).isNotEqualTo(PinningThreshold.of(2, 301L));
        assertThat(PinningThreshold.of(2, 300L)).isNotEqualTo("not-a-threshold");
    }

    @Test
    void identicalThresholdIsEqualToItself() {
        PinningThreshold threshold = PinningThreshold.of(1, 1L);
        assertThat(threshold).isEqualTo(threshold);
    }

    @Test
    void toStringExposesBothDimensions() {
        assertThat(PinningThreshold.of(2, 300L).toString())
                .contains("minPinnedCount=2")
                .contains("minDurationNanos=300");
    }
}
