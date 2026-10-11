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

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** TaskObservation 字段与转换。 */
class TaskObservationTest {

    @Test
    void wallTimeMillisConversion() {
        long nanos = TimeUnit.MILLISECONDS.toNanos(123);
        TaskObservation obs =
                new TaskObservation(PinningReport.empty(), nanos, TaskSpec.plain("x"), true, null);
        assertThat(obs.wallTimeMillis()).isEqualTo(123);
    }

    @Test
    void failureMessageRetained() {
        TaskObservation obs =
                new TaskObservation(PinningReport.empty(), 1, TaskSpec.plain("x"), false, "boom");
        assertThat(obs.succeeded()).isFalse();
        assertThat(obs.failureMessage()).isEqualTo("boom");
    }

    @Test
    void successHasNullMessage() {
        TaskObservation obs =
                new TaskObservation(PinningReport.empty(), 1, TaskSpec.plain("x"), true, null);
        assertThat(obs.succeeded()).isTrue();
        assertThat(obs.failureMessage()).isNull();
    }
}
