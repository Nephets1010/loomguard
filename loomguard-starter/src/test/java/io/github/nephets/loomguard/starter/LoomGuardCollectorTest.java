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
import io.github.nephets.loomguard.core.TaskObservation;
import io.github.nephets.loomguard.core.TaskSpec;
import io.github.nephets.loomguard.core.ThreadLocalReport;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** LoomGuardCollector 的有界保留语义。 */
class LoomGuardCollectorTest {

    private LoomGuardReport dummy() {
        return LoomGuardReport.of(
                new TaskObservation(
                        io.github.nephets.loomguard.core.PinningReport.empty(),
                        1,
                        TaskSpec.plain("x"),
                        true,
                        null),
                List.of(),
                ThreadLocalReport.of(0, 0, List.of()));
    }

    @Test
    void recordsAndReadsRecent() {
        LoomGuardCollector collector = new LoomGuardCollector();
        collector.record(dummy());
        collector.record(dummy());
        assertThat(collector.recent()).hasSize(2);
        assertThat(collector.latest()).isNotNull();
    }

    @Test
    void capacityIsBounded() {
        LoomGuardCollector collector = new LoomGuardCollector(3);
        for (int i = 0; i < 10; i++) {
            collector.record(dummy());
        }
        assertThat(collector.recent()).hasSize(3);
        assertThat(collector.latest()).isNotNull();
    }

    @Test
    void recordRejectsNullReport() {
        LoomGuardCollector collector = new LoomGuardCollector();
        assertThatThrownBy(() -> collector.record(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void latestIsMostRecentlyAdded() {
        LoomGuardCollector collector = new LoomGuardCollector();
        LoomGuardReport first = dummy();
        collector.record(first);
        collector.record(dummy());
        assertThat(collector.latest()).isNotSameAs(first);
    }
}
