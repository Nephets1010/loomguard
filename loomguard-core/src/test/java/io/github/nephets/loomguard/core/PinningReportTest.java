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

/** PinningReport 的基础语义。 */
class PinningReportTest {

    @Test
    void emptyHasNoPinning() {
        PinningReport report = PinningReport.empty();
        assertThat(report.anyPinned()).isFalse();
        assertThat(report.pinnedCount()).isZero();
        assertThat(report.events()).isEmpty();
    }

    @Test
    void ofRetainsEventsAndCount() {
        PinningEvent e = new PinningEvent(List.of("a#b"), 0, 0, Instant.now());
        PinningReport report = PinningReport.of(List.of(e, e));
        assertThat(report.anyPinned()).isTrue();
        assertThat(report.pinnedCount()).isEqualTo(2);
        assertThat(report.hotStacks()).hasSize(2);
    }

    @Test
    void ofIsDefensivelyCopied() {
        List<PinningEvent> mutable = new java.util.ArrayList<>();
        mutable.add(new PinningEvent(List.of("x"), 0, 0, Instant.now()));
        PinningReport report = PinningReport.of(mutable);
        mutable.clear();
        assertThat(report.pinnedCount()).isEqualTo(1);
    }
}
